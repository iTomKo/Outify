use crate::spotify::client::SpotifyClient;
use reqwest::Url;
use scraper::{Html, Selector};

const SHORTLINK: &str = "spotify.link";
const LINK: &str = "open.spotify.com";

impl SpotifyClient {
    pub async fn extract_socialsession_token(&self, url: String) -> Option<String> {
        let parsed = Url::parse(&url).ok()?;

        let resolved = if parsed.host_str()? == SHORTLINK {
            let response = self.client.get(parsed).send().await.ok()?;

            let html = response.text().await.ok()?;
            let document = Html::parse_document(&html);

            let selector = Selector::parse(r#"meta[property="og:url"]"#).ok()?;

            let og_url = document.select(&selector).next()?.value().attr("content")?;

            Url::parse(og_url).ok()?
        } else {
            parsed
        };

        if resolved.host_str()? != LINK {
            return None;
        }

        let segments: Vec<_> = resolved.path_segments()?.collect();

        if segments.first()? != &"socialsession" {
            return None;
        }

        Some(segments.get(1)?.to_string())
    }
}
