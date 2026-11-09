//! Wait for librespot's local request budget, without delaying successful reads.
use librespot_core::{Error, error::ErrorKind};
use std::time::{Duration, Instant};

pub async fn paced<T, F, R>(mut request: F) -> Result<T, Error>
where
    F: FnMut() -> R,
    R: Future<Output = Result<T, Error>>,
{
    let started = Instant::now();
    loop {
        match request().await {
            Err(error)
                if locally_limited(&error) && started.elapsed() < Duration::from_secs(60) =>
            {
                tokio::time::sleep(Duration::from_millis(500)).await;
            }
            result => return result,
        }
    }
}

fn locally_limited(error: &Error) -> bool {
    error.kind == ErrorKind::ResourceExhausted
        && error
            .error
            .to_string()
            .starts_with("rate limited for at least another")
}
