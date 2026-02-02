use crate::error::Result;
use oauth2::reqwest::async_http_client;
use oauth2::{
    basic::BasicClient, AuthUrl, AuthorizationCode, ClientId, ClientSecret, CsrfToken, RedirectUrl,
    RefreshToken, Scope, TokenResponse, TokenUrl,
};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use tracing::{debug, info};

const AUTH_URL: &str = "https://accounts.google.com/o/oauth2/v2/auth";
const TOKEN_URL: &str = "https://oauth2.googleapis.com/token";
const REDIRECT_URL: &str = "http://localhost:8085";

#[derive(Serialize, Deserialize, Clone)]
pub struct StoredToken {
    pub access_token: String,
    pub refresh_token: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expires_at: Option<i64>,
}

pub struct GoogleOAuthClient {
    client: BasicClient,
    token_file: PathBuf,
}

impl GoogleOAuthClient {
    pub fn new(client_id: String, client_secret: String) -> Result<Self> {
        let client = BasicClient::new(
            ClientId::new(client_id),
            Some(ClientSecret::new(client_secret)),
            AuthUrl::new(AUTH_URL.to_string())?,
            Some(TokenUrl::new(TOKEN_URL.to_string())?),
        )
        .set_redirect_uri(RedirectUrl::new(REDIRECT_URL.to_string())?);

        // Store token in same directory as credentials
        let mut token_file = dirs::config_dir().unwrap_or_else(|| PathBuf::from("."));
        token_file.push("remarkable2notion");
        fs::create_dir_all(&token_file)?;
        token_file.push("google_token.json");

        Ok(Self { client, token_file })
    }

    /// Load token from file if it exists
    pub fn load_token(&self) -> Result<Option<StoredToken>> {
        if !self.token_file.exists() {
            return Ok(None);
        }

        let content = fs::read_to_string(&self.token_file)?;
        let token: StoredToken = serde_json::from_str(&content)?;
        Ok(Some(token))
    }

    /// Save token to file
    fn save_token(&self, token: &StoredToken) -> Result<()> {
        let content = serde_json::to_string_pretty(token)?;
        fs::write(&self.token_file, content)?;

        // Set restrictive permissions (Unix only - 0o600 = rw-------)
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let permissions = std::fs::Permissions::from_mode(0o600);
            fs::set_permissions(&self.token_file, permissions)?;
        }

        debug!("Token saved to {:?}", self.token_file);
        Ok(())
    }

    /// Perform initial OAuth flow (opens browser)
    pub async fn authorize(&self) -> Result<StoredToken> {
        let (auth_url, csrf_token) = self
            .client
            .authorize_url(CsrfToken::new_random)
            .add_scope(Scope::new(
                "https://www.googleapis.com/auth/drive.file".to_string(),
            ))
            .url();

        info!("\n{}", "=".repeat(70));
        info!("GOOGLE DRIVE OAUTH2 AUTHENTICATION");
        info!("{}", "=".repeat(70));
        info!("\nPlease visit this URL to authorize the application:");
        info!("\n{}\n", auth_url);
        info!("Waiting for authorization...");
        info!("{}\n", "=".repeat(70));

        // Open browser automatically
        if let Err(e) = open::that(auth_url.as_str()) {
            info!("Could not open browser automatically: {}", e);
            info!("Please open the URL manually in your browser.");
        }

        // Start local server to receive callback
        let (code, state) = Self::receive_callback()?;

        // Verify CSRF token
        if state != *csrf_token.secret() {
            return Err(crate::error::Error::Io(std::io::Error::other(
                "CSRF token mismatch",
            )));
        }

        // Exchange authorization code for access token
        let token_result = self
            .client
            .exchange_code(AuthorizationCode::new(code))
            .request_async(async_http_client)
            .await
            .map_err(|e| crate::error::Error::OAuth(format!("Token exchange failed: {}", e)))?;

        let access_token = token_result.access_token().secret().to_string();
        let refresh_token = token_result
            .refresh_token()
            .ok_or_else(|| {
                crate::error::Error::Io(std::io::Error::other("No refresh token received"))
            })?
            .secret()
            .to_string();

        let expires_at = token_result
            .expires_in()
            .map(|duration| chrono::Utc::now().timestamp() + duration.as_secs() as i64);

        let stored_token = StoredToken {
            access_token,
            refresh_token,
            expires_at,
        };

        self.save_token(&stored_token)?;
        info!("\n✅ Authentication successful!");
        info!("Token saved to {:?}", self.token_file);

        Ok(stored_token)
    }

    /// Refresh access token using refresh token
    pub async fn refresh_token(&self, refresh_token: &str) -> Result<StoredToken> {
        debug!("Refreshing access token...");

        let token_result = self
            .client
            .exchange_refresh_token(&RefreshToken::new(refresh_token.to_string()))
            .request_async(async_http_client)
            .await
            .map_err(|e| crate::error::Error::OAuth(format!("Token refresh failed: {}", e)))?;

        let access_token = token_result.access_token().secret().to_string();

        // Refresh token might not be returned (keep the old one)
        let refresh_token = token_result
            .refresh_token()
            .map(|t| t.secret().to_string())
            .unwrap_or_else(|| refresh_token.to_string());

        let expires_at = token_result
            .expires_in()
            .map(|duration| chrono::Utc::now().timestamp() + duration.as_secs() as i64);

        let stored_token = StoredToken {
            access_token,
            refresh_token,
            expires_at,
        };

        self.save_token(&stored_token)?;
        debug!("Access token refreshed successfully");

        Ok(stored_token)
    }

    /// Get valid access token (refreshes if expired)
    pub async fn get_valid_token(&self) -> Result<StoredToken> {
        if let Some(token) = self.load_token()? {
            // Check if token is expired or will expire soon (within 5 minutes)
            let needs_refresh = if let Some(expires_at) = token.expires_at {
                let now = chrono::Utc::now().timestamp();
                expires_at - now < 300 // Refresh if less than 5 minutes remaining
            } else {
                false
            };

            if needs_refresh {
                info!("Access token expired, refreshing...");
                self.refresh_token(&token.refresh_token).await
            } else {
                Ok(token)
            }
        } else {
            info!("No token found, starting authorization flow...");
            self.authorize().await
        }
    }

    /// Start local HTTP server to receive OAuth callback
    fn receive_callback() -> Result<(String, String)> {
        use tiny_http::{Response, Server};

        let server = Server::http("127.0.0.1:8085").map_err(|e| {
            crate::error::Error::Io(std::io::Error::other(format!(
                "Failed to start callback server: {}",
                e
            )))
        })?;

        // Wait for exactly one request
        let request = server.recv().map_err(|e| {
            crate::error::Error::Io(std::io::Error::other(format!(
                "Failed to receive callback: {}",
                e
            )))
        })?;

        let url = format!("http://localhost:8085{}", request.url());
        let parsed_url = url::Url::parse(&url)?;

        let code = parsed_url
            .query_pairs()
            .find(|(key, _)| key == "code")
            .map(|(_, value)| value.to_string())
            .ok_or_else(|| {
                crate::error::Error::Io(std::io::Error::other("No authorization code in callback"))
            })?;

        let state = parsed_url
            .query_pairs()
            .find(|(key, _)| key == "state")
            .map(|(_, value)| value.to_string())
            .ok_or_else(|| {
                crate::error::Error::Io(std::io::Error::other("No state in callback"))
            })?;

        // Send success response to browser
        let response = Response::from_string(
            "<html><body><h1>✅ Authorization successful!</h1>\
             <p>You can close this window and return to the terminal.</p></body></html>",
        );
        request.respond(response).map_err(|e| {
            crate::error::Error::Io(std::io::Error::other(format!(
                "Failed to send response: {}",
                e
            )))
        })?;

        Ok((code, state))
    }
}
