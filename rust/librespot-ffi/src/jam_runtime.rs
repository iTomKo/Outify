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
    jam::{self, Jam, JoinMode},
    jni_utils::jni_bridge::{self, BridgeArg, JavaCallback},
};

const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);
static MANAGER: Lazy<Mutex<Manager>> = Lazy::new(|| Mutex::new(Manager::default()));

#[derive(Clone, Debug)]
pub enum Operation {
    Start,
    Current,
    Leave,
    Kick(String),
    Info(String),
    Join(String, JoinMode),
}

// Startup/reconnect may restore membership, but must never create a social
// session. Only an explicit Start/Invite action is allowed to do that.
const STARTUP_OPERATION: Operation = Operation::Current;

impl Operation {
    fn name(&self) -> &'static str {
        match self {
            Self::Start => "Start",
            Self::Current => "Current",
            Self::Leave => "Leave",
            Self::Kick(_) => "Kick",
            Self::Info(_) => "Info",
            Self::Join(_, _) => "Join",
        }
    }

    async fn execute<'a, K, I, J>(
        &'a self,
        start: impl Future<Output = Result<Jam, Error>>,
        current: impl Future<Output = Result<Option<Jam>, Error>>,
        leave: impl Future<Output = Result<(), Error>>,
        kick: impl FnOnce(&'a str) -> K,
        info: impl FnOnce(&'a str) -> I,
        join: impl FnOnce(&'a str, JoinMode) -> J,
    ) -> Result<Option<Jam>, Error>
    where
        K: Future<Output = Result<(), Error>>,
        I: Future<Output = Result<Jam, Error>>,
        J: Future<Output = Result<Jam, Error>>,
    {
        match self {
            Self::Start => start.await.map(Some),
            Self::Current => current.await,
            Self::Leave => leave.await.map(|()| None),
            Self::Kick(member_id) => {
                kick(member_id).await?;
                current.await
            }
            Self::Info(token) => info(token).await.map(Some),
            Self::Join(token, join_mode) => join(token, join_mode.clone()).await.map(Some),
        }
    }
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
        if self.jam != jam {
            match &jam {
                Some(jam) => info!(
                    "jam: state active (owner={}, members={})",
                    jam.owner,
                    jam.members.len()
                ),
                None => info!("jam: state inactive (left, ended, or runtime reset)"),
            }
        }
        self.revision += 1;
        self.jam = jam;
        self.notify();
        true
    }

    fn complete_request(
        &mut self,
        generation: u64,
        revision: u64,
        operation: &Operation,
        jam: Option<Jam>,
    ) -> Result<Option<Jam>, String> {
        if self.generation != generation {
            return Err("Jam session was replaced or shut down".into());
        }
        // Invitation previews belong only to the request callback, not the
        // active membership or its listener. Do not advance its revision either.
        if !matches!(operation, Operation::Info(_)) {
            // A successful leave is authoritative even if someone joined while
            // it was pending. Reads/starts must not overwrite newer dealer state.
            let revision = if matches!(operation, Operation::Leave) {
                None
            } else {
                Some(revision)
            };
            self.publish(generation, revision, jam.clone());
        }
        Ok(jam)
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

/// Starts the listener and restores an existing Jam, without creating one.
/// Called before the newly authenticated Spirc starts its dealer task.
pub fn initialize(session: &Session) -> Result<(), Error> {
    let mut manager = MANAGER.lock().unwrap();
    manager.reset();
    if session.is_invalid() {
        manager.error("cannot watch a shut down Jam session".into());
        return Err(Error::unavailable("cannot watch a shut down Jam session"));
    }
    info!("jam: checking for an existing session (read-only)");
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
        let initial = perform(task_context.clone(), STARTUP_OPERATION);
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
                    match result {
                        Ok(Some(jam)) => info!(
                            "jam: restored existing session (owner={}, members={})",
                            jam.owner,
                            jam.members.len()
                        ),
                        Ok(None) => info!("jam: no active session; continuing without a Jam"),
                        Err(error) => {
                            let manager = MANAGER.lock().unwrap();
                            if manager.generation == task_context.generation {
                                manager.error(error);
                            }
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

pub fn has_jam() -> bool {
    MANAGER.lock().unwrap().jam.is_some()
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
    async move {
        // Never log operation payloads, especially invitation tokens.
        let name = operation.name();
        info!("jam: {name} request started");
        let result = match context {
            Ok(context) => perform(context, operation.clone()).await,
            Err(error) => Err(error),
        };
        match &result {
            Ok(_) => info!("jam: {name} request succeeded"),
            Err(error) => warn!("jam: {name} request failed: {error}"),
        }
        result
    }
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
            let jam = operation.execute(
                jam::start(&context.session),
                jam::current(&context.session),
                jam::leave(&context.session),
                |member_id| jam::kick(&context.session, member_id),
                |token| jam::info(&context.session, token),
                |token, join_mode| jam::join(&context.session, token, join_mode),
            ).await.map_err(|error| error.to_string())?;
            let mut manager = MANAGER.lock().unwrap();
            manager.complete_request(context.generation, revision, &operation, jam)
        }) => result.unwrap_or_else(|_| Err("Jam request timed out".into())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn startup_lookup(current: Result<Option<Jam>, Error>) -> Result<Option<Jam>, Error> {
        STARTUP_OPERATION
            .execute(
                async { panic!("startup must not create a Jam") },
                async { current },
                async { panic!("startup must not leave an existing Jam") },
                |_| async { panic!("startup must not kick a member") },
                |_| async { panic!("startup must not preview an invitation") },
                |_, _| async { panic!("startup must not join a Jam") },
            )
            .await
    }

    #[tokio::test]
    async fn kick_passes_member_id_and_returns_refreshed_jam() {
        let jam = Jam {
            owner: true,
            ..Default::default()
        };
        let kicked = std::cell::Cell::new(false);
        let kicked_ref = &kicked;
        let result = Operation::Kick("member-id".into())
            .execute(
                async { panic!("kick must not start a Jam") },
                async {
                    assert!(kicked.get(), "refresh must happen after kicking");
                    Ok(Some(jam.clone()))
                },
                async { panic!("kick must not leave the Jam") },
                |member_id| async move {
                    assert_eq!(member_id, "member-id");
                    kicked_ref.set(true);
                    Ok(())
                },
                |_| async { panic!("kick must not preview an invitation") },
                |_, _| async { panic!("kick must not join a Jam") },
            )
            .await
            .unwrap();
        assert_eq!(result, Some(jam));
    }

    #[tokio::test]
    async fn startup_reuses_an_existing_jam_without_starting_another() {
        let jam = Jam {
            owner: false,
            members: vec![jam::JamMember {
                id: "host".into(),
                host: true,
                ..Default::default()
            }],
            ..Default::default()
        };
        assert_eq!(
            startup_lookup(Ok(Some(jam.clone()))).await.unwrap(),
            Some(jam)
        );
    }

    #[tokio::test]
    async fn startup_without_a_jam_does_not_create_one() {
        assert_eq!(startup_lookup(Ok(None)).await.unwrap(), None);
    }

    #[tokio::test]
    async fn failed_startup_lookup_never_falls_back_to_creating_a_jam() {
        let result = startup_lookup(Err(Error::unavailable("offline"))).await;
        assert_eq!(
            result.unwrap_err().kind,
            librespot_core::error::ErrorKind::Unavailable
        );
    }

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
