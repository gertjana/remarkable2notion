use crate::error::{Error, Result};
use crate::oauth::GoogleOAuthClient;
use reqwest::Client;
use serde_json::json;
use std::path::Path;
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{debug, warn};

pub struct GoogleDriveClient {
    client: Client,
    oauth_client: Arc<GoogleOAuthClient>,
    access_token: Arc<RwLock<String>>,
    folder_id: Option<String>,
}

impl GoogleDriveClient {
    pub async fn new(
        oauth_client: Arc<GoogleOAuthClient>,
        folder_id: Option<String>,
    ) -> Result<Self> {
        // Get valid token (will refresh if needed)
        let token = oauth_client.get_valid_token().await?;

        Ok(Self {
            client: Client::new(),
            oauth_client,
            access_token: Arc::new(RwLock::new(token.access_token)),
            folder_id,
        })
    }

    /// Get current access token
    async fn get_token(&self) -> String {
        self.access_token.read().await.clone()
    }

    /// Refresh the access token if it's expired
    async fn refresh_token_if_needed(&self) -> Result<()> {
        warn!("Google Drive token expired, attempting automatic refresh...");

        // Load current token to get refresh token
        let stored_token = self
            .oauth_client
            .load_token()?
            .ok_or_else(|| Error::Io(std::io::Error::other("No stored token found")))?;

        // Refresh using OAuth client
        let new_token = self
            .oauth_client
            .refresh_token(&stored_token.refresh_token)
            .await?;

        // Update in-memory token
        *self.access_token.write().await = new_token.access_token;

        debug!("Token refreshed successfully");
        Ok(())
    }

    pub async fn upload_pdf(&self, pdf_path: &Path, notebook_name: &str) -> Result<String> {
        debug!("Uploading PDF to Google Drive: {}", notebook_name);
        self.upload_file(
            pdf_path,
            &format!("{}.pdf", notebook_name),
            "application/pdf",
        )
        .await
    }

    async fn upload_file(
        &self,
        file_path: &Path,
        filename: &str,
        mime_type: &str,
    ) -> Result<String> {
        // Try upload, retry once if token is expired
        match self
            .upload_file_internal(file_path, filename, mime_type)
            .await
        {
            Ok(url) => Ok(url),
            Err(e) => {
                // Check if it's a 401 Unauthorized error
                if e.to_string().contains("401") {
                    // Attempt token refresh
                    self.refresh_token_if_needed().await?;

                    // Retry the upload with new token
                    debug!("Retrying upload with refreshed token...");
                    self.upload_file_internal(file_path, filename, mime_type)
                        .await
                } else {
                    Err(e)
                }
            }
        }
    }

    async fn upload_file_internal(
        &self,
        file_path: &Path,
        filename: &str,
        mime_type: &str,
    ) -> Result<String> {
        let file_bytes = tokio::fs::read(file_path).await?;

        // Prepare metadata
        let mut metadata = json!({
            "name": filename,
            "mimeType": mime_type
        });

        if let Some(folder_id) = &self.folder_id {
            metadata["parents"] = json!([folder_id]);
        }

        // Create multipart upload
        let metadata_part =
            reqwest::multipart::Part::text(metadata.to_string()).mime_str("application/json")?;

        let file_part = reqwest::multipart::Part::bytes(file_bytes)
            .file_name(filename.to_string())
            .mime_str(mime_type)?;

        let form = reqwest::multipart::Form::new()
            .part("metadata", metadata_part)
            .part("file", file_part);

        // Upload file
        let response = self
            .client
            .post("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
            .bearer_auth(&self.get_token().await)
            .multipart(form)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await?;
            return Err(Error::Io(std::io::Error::other(format!(
                "Google Drive upload failed: {} - {}",
                status, body
            ))));
        }

        let result: serde_json::Value = response.json().await?;
        let file_id = result["id"].as_str().ok_or_else(|| {
            Error::Io(std::io::Error::other("No file ID in Google Drive response"))
        })?;

        debug!("File uploaded to Google Drive with ID: {}", file_id);

        // Make file publicly readable and get shareable link
        let share_url = self.make_file_public(file_id).await?;

        debug!("File uploaded to Google Drive: {}", share_url);
        Ok(share_url)
    }

    async fn make_file_public(&self, file_id: &str) -> Result<String> {
        // Create permission for anyone with link
        let permission_body = json!({
            "role": "reader",
            "type": "anyone"
        });

        let response = self
            .client
            .post(format!(
                "https://www.googleapis.com/drive/v3/files/{}/permissions",
                file_id
            ))
            .bearer_auth(&self.get_token().await)
            .json(&permission_body)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await?;
            return Err(Error::Io(std::io::Error::other(format!(
                "Failed to make file public: {} - {}",
                status, body
            ))));
        }

        // Return direct link to image (for embedding)
        Ok(format!(
            "https://drive.google.com/uc?export=view&id={}",
            file_id
        ))
    }
}
