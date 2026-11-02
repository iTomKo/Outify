//! Session-scoped Jam state, request serialization, and JNI notifications.

use std::{
    sync::{Arc, Mutex},
    time::Duration,
};

use librespot_core::{Error, Session};
use once_cell::sync::Lazy;
use tokio::{
    sync::{mpsc, watch},
    task::JoinHandle,
};

use crate::{
    jam::{self, Jam},
    jni_utils::jni_bridge::{self, BridgeArg, JavaCallback},
};

const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);
static MANAGER: Lazy<Mutex<Manager>> = Lazy::new(|| Mutex::new(Manager::default()));

#[derive(Clone, Copy)]
pub enum Operation {
    Start,
    Current,
    Leave,
}

struct Runtime {
    context: Context,
    cancel: watch::Sender<bool>,
    task: JoinHandle<()>,
}

#[derive(Clone)]
struct Context {
    generation: u64,
    session: Session,
    operations: Arc<tokio::sync::Mutex<()>>,
    cancelled: watch::Receiver<bool>,
}

#[derive(Default)]
struct Manager {
    generation: u64,
    revision: u64,
    jam: Option<Jam>,
    runtime: Option<Runtime>,
    listener: Option<Arc<JavaCallback>>,
}

impl Manager {
    fn notify(&self) {
        if let Some(listener) = &self.listener {
            match serde_json::to_string(&self.jam) {
                Ok(json) => jni_bridge::dispatch(listener.clone(), 0, vec![BridgeArg::Str(json)]),
                Err(error) => error!("jam: cannot serialize state: {error}"),
            }
        }
    }

    fn error(&self, message: String) {
        warn!("jam: {message}");
        if let Some(listener) = &self.listener {
            jni_bridge::dispatch(listener.clone(), 1, vec![BridgeArg::Str(message)]);
        }
    }

    fn publish(&mut self, generation: u64, revision: Option<u64>, jam: Option<Jam>) -> bool {
        if self.generation != generation || revision.is_some_and(|r| r != self.revision) {
            return false;
        }
        self.revision += 1;
        self.jam = jam;
        self.notify();
        true
    }

    fn reset(&mut self) {
        // Invalidate in-flight results before cancelling their tasks. Dispatch
        // under this same lock so a stale snapshot cannot be queued after clear.
        self.generation += 1;
        if let Some(runtime) = self.runtime.take() {
            let _ = runtime.cancel.send(true);
            runtime.task.abort();
        }
        self.publish(self.generation, None, None);
    }
}

pub fn register_listener(listener: Arc<JavaCallback>) {
    let mut manager = MANAGER.lock().unwrap();
    manager.listener = Some(listener);
    manager.notify();
}

pub fn unregister_listener() {
    MANAGER.lock().unwrap().listener = None;
}

/// Called before the newly authenticated Spirc starts its dealer task.
pub fn start(session: &Session) -> Result<(), Error> {
    let mut manager = MANAGER.lock().unwrap();
    manager.reset();
    if session.is_invalid() {
        manager.error("cannot watch a shut down Jam session".into());
        return Err(Error::unavailable("cannot watch a shut down Jam session"));
    }
    let (tx, mut updates) = mpsc::unbounded_channel();
    let watcher = jam::watch(session.clone(), tx).inspect_err(|error| {
        manager.error(format!("cannot start Jam listener: {error}"));
    })?;
    let (cancel, cancelled) = watch::channel(false);
    let context = Context {
        generation: manager.generation,
        session: session.clone(),
        operations: Arc::new(tokio::sync::Mutex::new(())),
        cancelled,
    };
    let task_context = context.clone();
    let mut shutdown = session.subscribe_shutdown();
    let task = tokio::spawn(async move {
        let mut cancelled = task_context.cancelled.clone();
        let initial = perform(task_context.clone(), Operation::Current);
        tokio::pin!(watcher, initial);
        let mut initial_done = false;
        let error = loop {
            tokio::select! {
                biased;
                _ = cancelled.changed() => break None,
                _ = shutdown.changed() => break None,
                update = updates.recv() => match update {
                    Some(jam) => {
                        MANAGER.lock().unwrap().publish(task_context.generation, None, jam);
                    }
                    None => break Some("Jam update channel closed".to_string()),
                },
                _ = &mut watcher => break Some("Jam dealer subscription closed".to_string()),
                result = &mut initial, if !initial_done => {
                    initial_done = true;
                    if let Err(error) = result {
                        let manager = MANAGER.lock().unwrap();
                        if manager.generation == task_context.generation {
                            manager.error(error);
                        }
                    }
                },
            }
        };
        let mut manager = MANAGER.lock().unwrap();
        if manager.generation == task_context.generation {
            manager.reset();
            if let Some(error) = error {
                manager.error(error);
            }
        }
    });
    manager.runtime = Some(Runtime {
        context,
        cancel,
        task,
    });
    Ok(())
}

pub fn stop() {
    MANAGER.lock().unwrap().reset();
}

pub fn request(operation: Operation) -> impl Future<Output = Result<Option<Jam>, String>> + Send {
    let context = MANAGER
        .lock()
        .unwrap()
        .runtime
        .as_ref()
        .map(|runtime| runtime.context.clone())
        .ok_or_else(|| "Jam is unavailable: the Connect session is not running".to_string());
    // Capture the session at JNI submission time, not when the spawned task is
    // eventually polled (which may be after a rebuild).
    async move { perform(context?, operation).await }
}

async fn perform(mut context: Context, operation: Operation) -> Result<Option<Jam>, String> {
    let mut shutdown = context.session.subscribe_shutdown();
    if *context.cancelled.borrow() || context.session.is_invalid() {
        return Err("Jam session was replaced or shut down".into());
    }
    tokio::select! {
        biased;
        _ = context.cancelled.changed() => Err("Jam session was replaced or shut down".into()),
        _ = shutdown.changed() => Err("Jam session was replaced or shut down".into()),
        result = tokio::time::timeout(REQUEST_TIMEOUT, async {
            // Serializes the initial read and explicit operations. Dealer updates
            // remain live while HTTP is pending and take precedence over reads.
            let _operation = context.operations.lock().await;
            while context.session.connection_id().is_empty() {
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
            let revision = {
                let manager = MANAGER.lock().unwrap();
                if manager.generation != context.generation {
                    return Err("Jam session was replaced or shut down".to_string());
                }
                manager.revision
            };
            let jam = match operation {
                Operation::Start => jam::start(&context.session).await.map(Some),
                Operation::Current => jam::current(&context.session).await,
                Operation::Leave => jam::leave(&context.session).await.map(|()| None),
            }.map_err(|error| error.to_string())?;
            let mut manager = MANAGER.lock().unwrap();
            if manager.generation != context.generation {
                return Err("Jam session was replaced or shut down".to_string());
            }
            // A successful leave is authoritative even if someone joined while
            // it was pending. Reads/starts must not overwrite newer dealer state.
            let revision = if matches!(operation, Operation::Leave) { None } else { Some(revision) };
            manager.publish(context.generation, revision, jam.clone());
            Ok(jam)
        }) => result.unwrap_or_else(|_| Err("Jam request timed out".into())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn late_http_snapshots_do_not_overwrite_dealer_updates() {
        let mut manager = Manager::default();
        let revision = manager.revision;
        let jam = Jam {
            owner: true,
            ..Default::default()
        };
        assert!(manager.publish(0, None, Some(jam.clone())));
        assert!(!manager.publish(0, Some(revision), None));
        assert_eq!(manager.jam, Some(jam));
    }

    #[test]
    fn shutdown_clears_state_and_rejects_old_session_updates() {
        let mut manager = Manager::default();
        assert!(manager.publish(0, None, Some(Jam::default())));
        manager.reset();
        assert!(manager.jam.is_none());
        assert!(!manager.publish(0, None, Some(Jam::default())));
        assert!(manager.publish(manager.generation, None, Some(Jam::default())));
    }

    #[test]
    fn successful_leave_clears_a_more_recent_snapshot() {
        let mut manager = Manager::default();
        manager.publish(0, None, Some(Jam::default()));
        assert!(manager.publish(0, None, None));
        assert!(manager.jam.is_none());
    }

    #[tokio::test]
    async fn teardown_cancels_requests_even_while_waiting_for_another_operation() {
        let (cancel, cancelled) = watch::channel(false);
        let operations = Arc::new(tokio::sync::Mutex::new(()));
        let _busy = operations.lock().await;
        let context = Context {
            generation: 0,
            session: Session::new(Default::default(), None),
            operations: operations.clone(),
            cancelled,
        };
        let request = tokio::spawn(perform(context, Operation::Current));
        tokio::task::yield_now().await;
        cancel.send(true).unwrap();
        let result = tokio::time::timeout(Duration::from_secs(1), request)
            .await
            .unwrap()
            .unwrap();
        assert!(result.unwrap_err().contains("shut down"));
    }

    #[tokio::test]
    async fn shutdown_cancels_requests_before_a_dealer_id_is_available() {
        let (_cancel, cancelled) = watch::channel(false);
        let session = Session::new(Default::default(), None);
        let context = Context {
            generation: 0,
            session: session.clone(),
            operations: Arc::new(tokio::sync::Mutex::new(())),
            cancelled,
        };
        let request = tokio::spawn(perform(context, Operation::Start));
        tokio::task::yield_now().await;
        session.shutdown();
        let result = tokio::time::timeout(Duration::from_secs(1), request)
            .await
            .unwrap()
            .unwrap();
        assert!(result.unwrap_err().contains("shut down"));
    }
}
