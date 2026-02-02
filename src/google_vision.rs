use crate::error::{Error, Result};
use reqwest::Client;
use serde_json::json;
use std::path::{Path, PathBuf};
use tracing::{debug, warn};

pub struct GoogleVisionClient {
    client: Client,
    api_key: String,
}

impl GoogleVisionClient {
    pub fn new(api_key: String) -> Self {
        Self {
            client: Client::new(),
            api_key,
        }
    }

    /// Extract text AND keep images from PDF (for uploading to Notion)
    pub async fn extract_text_and_images_from_pdf(
        &self,
        pdf_path: &Path,
    ) -> Result<(String, Vec<PathBuf>)> {
        debug!("Extracting text using Google Cloud Vision: {:?}", pdf_path);

        // First, extract images from PDF using pdftoppm
        let page_images = self.extract_images_from_pdf(pdf_path)?;

        if page_images.is_empty() {
            return Ok(("(No pages found in PDF)".to_string(), Vec::new()));
        }

        debug!(
            "Processing {} pages with Google Cloud Vision",
            page_images.len()
        );

        let mut full_text = String::new();

        // Process each page image
        for (i, image_path) in page_images.iter().enumerate() {
            debug!("Processing page {} of {}", i + 1, page_images.len());

            match self.extract_text_from_image(image_path).await {
                Ok(text) => {
                    if !text.trim().is_empty() {
                        if !full_text.is_empty() {
                            full_text.push_str(&format!("\n\n--- Page {} ---\n\n", i + 1));
                        }
                        full_text.push_str(&text);
                    }
                }
                Err(e) => {
                    warn!("Failed to process page {}: {}", i + 1, e);
                }
            }
        }

        if full_text.trim().is_empty() {
            warn!("No text extracted from PDF");
            full_text = "(No text detected)".to_string();
        } else {
            debug!(
                "Extracted {} characters using Google Cloud Vision",
                full_text.len()
            );
        }

        Ok((full_text, page_images))
    }

    /// Extract text from a single image using Vision API
    async fn extract_text_from_image(&self, image_path: &Path) -> Result<String> {
        // Read image and encode to base64
        let image_bytes = tokio::fs::read(image_path).await?;
        let image_base64 =
            base64::Engine::encode(&base64::engine::general_purpose::STANDARD, &image_bytes);

        // Call Vision API for image annotation
        let request_body = json!({
            "requests": [{
                "image": {
                    "content": image_base64
                },
                "features": [{
                    "type": "DOCUMENT_TEXT_DETECTION"
                }]
            }]
        });

        let url = format!(
            "https://vision.googleapis.com/v1/images:annotate?key={}",
            self.api_key
        );

        let response = self.client.post(&url).json(&request_body).send().await?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await?;
            return Err(Error::Ocr(format!(
                "Google Vision API failed: {} - {}",
                status, body
            )));
        }

        let result: serde_json::Value = response.json().await?;

        // Extract text from response
        if let Some(responses) = result["responses"].as_array() {
            if let Some(first_response) = responses.first() {
                if let Some(text) = first_response["fullTextAnnotation"]["text"].as_str() {
                    return Ok(text.to_string());
                }
            }
        }

        Ok(String::new())
    }

    /// Extract images from PDF pages using pdftoppm
    fn extract_images_from_pdf(&self, pdf_path: &Path) -> Result<Vec<PathBuf>> {
        use std::process::Command;

        let temp_dir = std::env::temp_dir();
        let base_name = pdf_path
            .file_stem()
            .and_then(|s| s.to_str())
            .ok_or_else(|| Error::Ocr("Invalid PDF filename".to_string()))?;

        let image_prefix = temp_dir.join(format!("{}_page", base_name));

        debug!("Converting PDF to images using pdftoppm");

        // Convert PDF to PNG images (one per page)
        let status = Command::new("pdftoppm")
            .arg("-png")
            .arg(pdf_path)
            .arg(&image_prefix)
            .status()
            .map_err(|e| Error::Ocr(format!("Failed to run pdftoppm: {}", e)))?;

        if !status.success() {
            return Err(Error::Ocr("PDF to image conversion failed".to_string()));
        }

        // Find all generated PNG files
        let parent_dir = image_prefix.parent().unwrap();
        let prefix_name = image_prefix.file_name().unwrap().to_str().unwrap();

        let mut page_images: Vec<_> = std::fs::read_dir(parent_dir)?
            .filter_map(|e| e.ok())
            .filter(|e| {
                e.file_name()
                    .to_str()
                    .map(|s| s.starts_with(prefix_name) && s.ends_with(".png"))
                    .unwrap_or(false)
            })
            .map(|e| e.path())
            .collect();

        page_images.sort();

        if page_images.is_empty() {
            return Err(Error::Ocr("No images generated from PDF".to_string()));
        }

        debug!("Extracted {} page images", page_images.len());
        Ok(page_images)
    }
}
