use crate::config::Config;
use crate::error::{Error, Result};
use crate::google_drive::GoogleDriveClient;
use crate::google_vision::GoogleVisionClient;
use crate::notion::NotionClient;
use crate::oauth::GoogleOAuthClient;
use crate::remarkable::{Notebook, RemarkableClient};
use std::path::Path;
use std::sync::Arc;
use tracing::{debug, error, info, warn};

pub struct SyncEngine {
    config: Config,
    remarkable: RemarkableClient,
    google_vision: GoogleVisionClient,
    google_drive: Option<GoogleDriveClient>,
    notion: NotionClient,
}

impl SyncEngine {
    pub async fn new(config: Config) -> Result<Self> {
        let remarkable = RemarkableClient::new(
            config.remarkable_backup_dir.clone(),
            config.remarkable_password.clone(),
        )
        .await?;

        // Google Cloud Vision is required
        let google_vision = if let Some(ref api_key) = config.google_vision_api_key {
            debug!("Using Google Cloud Vision for OCR");
            GoogleVisionClient::new(api_key.clone())
        } else {
            return Err(Error::Config(
                "Google Cloud Vision API key is required. Set GOOGLE_VISION_API_KEY in .env file.".to_string()
            ));
        };

        // Setup Google Drive if OAuth credentials are provided
        let google_drive = if let (Some(client_id), Some(client_secret)) = (
            &config.google_oauth_client_id,
            &config.google_oauth_client_secret,
        ) {
            debug!("Google Drive integration enabled");
            let oauth_client = Arc::new(GoogleOAuthClient::new(
                client_id.clone(),
                client_secret.clone(),
            )?);
            Some(GoogleDriveClient::new(
                oauth_client,
                config.google_drive_folder_id.clone(),
            ).await?)
        } else {
            warn!("Google Drive not configured - PDFs will be linked locally");
            None
        };

        let notion = NotionClient::new(
            config.notion_token.clone(),
            config.notion_database_id.clone(),
        );

        Ok(Self {
            config,
            remarkable,
            google_vision,
            google_drive,
            notion,
        })
    }

    pub async fn verify_prerequisites(&self) -> Result<()> {
        debug!("Verifying prerequisites...");

        self.remarkable.check_installation().await?;

        self.notion.verify_connection().await?;

        // Ensure database has required properties
        self.notion.ensure_database_properties().await?;

        debug!("All prerequisites verified");
        Ok(())
    }

    pub async fn sync(&self) -> Result<()> {
        let notebooks = self.remarkable.list_notebooks().await?;

        if notebooks.is_empty() {
            warn!("No notebooks found");
            return Ok(());
        }

        info!("Syncing {} notebooks", notebooks.len());

        let mut success_count = 0;
        let mut error_count = 0;

        for (idx, notebook) in notebooks.iter().enumerate() {
            debug!("Processing {}/{}: {}", idx + 1, notebooks.len(), notebook.name);

            match self.process_notebook(notebook).await {
                Ok(_) => {
                    success_count += 1;
                    info!("✓ {}", notebook.name);
                }
                Err(e) => {
                    error_count += 1;
                    error!("✗ {} - {}", notebook.name, e);
                }
            }
        }

        info!(
            "Complete: {} succeeded, {} failed",
            success_count, error_count
        );

        Ok(())
    }

    async fn process_notebook(&self, notebook: &Notebook) -> Result<()> {
        if self.config.dry_run {
            debug!("[DRY RUN] Would process: {}", notebook.name);
            return Ok(());
        }

        let pdf_path = self
            .remarkable
            .download_notebook(notebook, &self.config.temp_dir)
            .await?;

        // Extract text and images using Google Cloud Vision
        let (text_content, page_images) = self.google_vision.extract_text_and_images_from_pdf(&pdf_path).await?;

        // Prepare image paths for direct upload to Notion
        let image_paths: Vec<(usize, &Path)> = page_images
            .iter()
            .enumerate()
            .map(|(idx, path)| (idx + 1, path.as_path()))
            .collect();

        // Upload PDF to Google Drive if configured
        let pdf_url = if let Some(ref drive) = self.google_drive {
            Some(drive.upload_pdf(&pdf_path, &notebook.name).await?)
        } else {
            None
        };

        let existing_page = self.notion.find_page_by_title(&notebook.name).await?;

        match existing_page {
            Some(page) => {
                debug!("Updating existing page: {}", notebook.name);
                self.notion.update_page(&page.id, &text_content, &notebook.tags).await?;

                // Add images if available (upload directly to Notion)
                if !image_paths.is_empty() {
                    self.notion.add_uploaded_images(&page.id, &image_paths).await?;
                }

                // Set PDF URL (Google Drive link or local path)
                if let Some(ref url) = pdf_url {
                    self.notion.set_pdf_url(&page.id, url).await?;
                } else {
                    self.notion.upload_pdf(&page.id, &pdf_path).await?;
                    self.notion.set_pdf_link(&page.id, &pdf_path).await?;
                }
            }
            None => {
                debug!("Creating new page: {}", notebook.name);
                let page = self.notion.create_page(&notebook.name, &text_content, &notebook.metadata, &notebook.tags).await?;

                // Add images if available (upload directly to Notion)
                if !image_paths.is_empty() {
                    self.notion.add_uploaded_images(&page.id, &image_paths).await?;
                }

                // Set PDF URL (Google Drive link or local path)
                if let Some(ref url) = pdf_url {
                    self.notion.set_pdf_url(&page.id, url).await?;
                } else {
                    self.notion.upload_pdf(&page.id, &pdf_path).await?;
                    self.notion.set_pdf_link(&page.id, &pdf_path).await?;
                }
            }
        }

        // Clean up temporary image files
        for (_, image_path) in &image_paths {
            std::fs::remove_file(image_path).ok();
        }

        std::fs::remove_file(&pdf_path)?;

        Ok(())
    }
}
