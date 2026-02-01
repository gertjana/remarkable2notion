use crate::error::{Error, Result};
use std::path::PathBuf;

#[derive(Debug, Clone)]
pub struct Config {
    pub notion_token: String,
    pub notion_database_id: String,
    pub remarkable_backup_dir: Option<PathBuf>,
    pub remarkable_password: Option<String>,
    pub google_oauth_client_id: Option<String>,
    pub google_oauth_client_secret: Option<String>,
    pub google_drive_folder_id: Option<String>,
    pub google_vision_api_key: Option<String>,
    pub dry_run: bool,
    pub temp_dir: PathBuf,
}

impl Config {
    pub fn new(
        notion_token: String,
        notion_database_id: String,
        remarkable_backup_dir: Option<PathBuf>,
        remarkable_password: Option<String>,
        dry_run: bool,
        _verbose: bool,
    ) -> Result<Self> {
        if notion_token.is_empty() {
            return Err(Error::Config("Notion token is required".to_string()));
        }
        if notion_database_id.is_empty() {
            return Err(Error::Config("Notion database ID is required".to_string()));
        }

        let temp_dir = std::env::temp_dir().join("remarkable2notion");
        std::fs::create_dir_all(&temp_dir)?;

        // Optional Google integrations
        let google_oauth_client_id = std::env::var("GOOGLE_OAUTH_CLIENT_ID").ok();
        let google_oauth_client_secret = std::env::var("GOOGLE_OAUTH_CLIENT_SECRET").ok();
        let google_drive_folder_id = std::env::var("GOOGLE_DRIVE_FOLDER_ID").ok();
        let google_vision_api_key = std::env::var("GOOGLE_VISION_API_KEY").ok();

        Ok(Self {
            notion_token,
            notion_database_id,
            remarkable_backup_dir,
            remarkable_password,
            google_oauth_client_id,
            google_oauth_client_secret,
            google_drive_folder_id,
            google_vision_api_key,
            dry_run,
            temp_dir,
        })
    }
}
