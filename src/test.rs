use crate::error::Result;
use crate::google_vision::GoogleVisionClient;
use crate::notion::NotionClient;
use crate::remarkable::RemarkableClient;
use std::path::{Path, PathBuf};
use tracing::{info, warn};

pub async fn test_remarkable(backup_dir: Option<PathBuf>, password: Option<String>) -> Result<()> {
    info!("Testing RemarkableSync...");
    let client = RemarkableClient::new(backup_dir, password).await?;
    client.check_installation().await?;

    info!("Listing notebooks from ReMarkable tablet...");
    info!("⚠️  Make sure your tablet is connected via USB!");
    let notebooks = client.list_notebooks().await?;

    for notebook in &notebooks {
        info!("  - {} (path: {})", notebook.name, notebook.path);
    }

    Ok(())
}

pub async fn test_ocr(pdf_path: &Path) -> Result<()> {
    info!("Testing Google Cloud Vision OCR...");

    let api_key = std::env::var("GOOGLE_VISION_API_KEY")
        .map_err(|_| crate::error::Error::Config(
            "GOOGLE_VISION_API_KEY not set in environment".to_string()
        ))?;

    let vision = GoogleVisionClient::new(api_key);
    let (text, _images) = vision.extract_text_and_images_from_pdf(pdf_path).await?;

    info!("Extracted {} characters", text.len());
    info!("Preview: {}", &text.chars().take(200).collect::<String>());

    Ok(())
}

pub async fn test_notion(token: &str, database_id: &str) -> Result<()> {
    info!("Testing Notion API...");
    let client = NotionClient::new(token.to_string(), database_id.to_string());

    client.verify_connection().await?;
    info!("✓ Connection verified");

    let test_page = client.find_page_by_title("Test Page").await?;
    if let Some(page) = test_page {
        info!("✓ Found existing test page: {}", page.id);
    } else {
        warn!("No test page found");
    }

    Ok(())
}
