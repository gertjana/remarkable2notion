use crate::error::{Error, Result};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::path::Path;
use tracing::{debug, warn};

const NOTION_API_VERSION: &str = "2022-06-28";
const NOTION_API_BASE: &str = "https://api.notion.com/v1";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NotionPage {
    pub id: String,
    pub title: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NotebookMetadata {
    pub created_time: Option<String>,
    pub modified_time: Option<String>,
    pub folder_path: String,
}

#[derive(Debug, Deserialize)]
struct QueryResponse {
    results: Vec<PageResult>,
}

#[derive(Debug, Deserialize)]
struct PageResult {
    id: String,
    properties: serde_json::Value,
}

#[derive(Debug, Deserialize)]
struct BlockResponse {
    results: Vec<serde_json::Value>,
}

pub struct NotionClient {
    client: Client,
    token: String,
    database_id: String,
}

impl NotionClient {
    pub fn new(token: String, database_id: String) -> Self {
        let client = Client::new();
        Self {
            client,
            token,
            database_id,
        }
    }

    fn headers(&self) -> reqwest::header::HeaderMap {
        let mut headers = reqwest::header::HeaderMap::new();
        headers.insert(
            "Authorization",
            format!("Bearer {}", self.token).parse().unwrap(),
        );
        headers.insert("Notion-Version", NOTION_API_VERSION.parse().unwrap());
        headers.insert("Content-Type", "application/json".parse().unwrap());
        headers
    }

    pub async fn verify_connection(&self) -> Result<()> {
        debug!("Verifying Notion API connection");

        let response = self
            .client
            .get(format!("{}/databases/{}", NOTION_API_BASE, self.database_id))
            .headers(self.headers())
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await?;
            return Err(Error::Notion(format!(
                "Failed to verify Notion connection: {} - {}",
                status, body
            )));
        }

        debug!("Notion connection verified");
        Ok(())
    }

    pub async fn ensure_database_properties(&self) -> Result<()> {
        debug!("Ensuring database has required properties");

        let update_body = json!({
            "properties": {
                "PDF Link": {
                    "url": {}
                },
                "Tags": {
                    "multi_select": {
                        "options": []
                    }
                },
                "Created": {
                    "date": {}
                },
                "Last Modified": {
                    "date": {}
                }
            }
        });

        let response = self
            .client
            .patch(format!("{}/databases/{}", NOTION_API_BASE, self.database_id))
            .headers(self.headers())
            .json(&update_body)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await?;
            warn!("Failed to update database schema (may already exist): {} - {}", status, body);
        } else {
            debug!("Database properties ensured");
        }

        Ok(())
    }

    async fn get_title_property_name(&self) -> Result<String> {
        // Get database schema to find the title property
        let response = self
            .client
            .get(format!("{}/databases/{}", NOTION_API_BASE, self.database_id))
            .headers(self.headers())
            .send()
            .await?;

        if !response.status().is_success() {
            return Err(Error::Notion("Failed to get database schema".to_string()));
        }

        let db_info: serde_json::Value = response.json().await?;

        // Find the title property
        if let Some(properties) = db_info.get("properties").and_then(|p| p.as_object()) {
            for (key, value) in properties.iter() {
                if let Some(prop_type) = value.get("type").and_then(|t| t.as_str()) {
                    if prop_type == "title" {
                        return Ok(key.clone());
                    }
                }
            }
        }

        Err(Error::Notion("No title property found in database".to_string()))
    }

    pub async fn find_page_by_title(&self, title: &str) -> Result<Option<NotionPage>> {
        debug!("Searching for page with title: {}", title);

        // Query all pages and filter client-side since we don't know the exact property name
        let query_body = json!({
            "page_size": 100
        });

        let response = self
            .client
            .post(format!(
                "{}/databases/{}/query",
                NOTION_API_BASE, self.database_id
            ))
            .headers(self.headers())
            .json(&query_body)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await?;
            warn!("Query failed: {} - {}", status, body);
            return Ok(None);
        }

        let query_result: QueryResponse = response.json().await?;

        // Search through results for matching title
        for page in query_result.results {
            if let Some(props) = page.properties.as_object() {
                // Look through all properties to find title type
                for (_key, value) in props.iter() {
                    if let Some(prop_type) = value.get("type") {
                        if prop_type == "title" {
                            if let Some(title_array) = value.get("title").and_then(|t| t.as_array()) {
                                if let Some(first_title) = title_array.first() {
                                    if let Some(text_content) = first_title.get("plain_text").and_then(|t| t.as_str()) {
                                        if text_content == title {
                                            debug!("Found existing page with ID: {}", page.id);
                                            return Ok(Some(NotionPage {
                                                id: page.id.clone(),
                                                title: title.to_string(),
                                            }));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        debug!("No existing page found");
        Ok(None)
    }

    pub async fn create_page(&self, title: &str, content: &str, metadata: &NotebookMetadata, tags: &[String]) -> Result<NotionPage> {
        debug!("Creating Notion page: {}", title);

        // Get the actual title property name
        let title_prop_name = self.get_title_property_name().await?;

        let mut properties = json!({
            title_prop_name: {
                "title": [
                    {
                        "text": {
                            "content": title
                        }
                    }
                ]
            }
        });

        // Add tags if we have any
        if !tags.is_empty() {
            debug!("Adding {} tags: {:?}", tags.len(), tags);
            properties["Tags"] = json!({
                "multi_select": tags.iter().map(|tag| json!({"name": tag})).collect::<Vec<_>>()
            });
        }

        // Add creation date if available
        if let Some(ref created) = metadata.created_time {
            properties["Created"] = json!({
                "date": {
                    "start": created
                }
            });
        }

        // Add last modified date if available
        if let Some(ref modified) = metadata.modified_time {
            properties["Last Modified"] = json!({
                "date": {
                    "start": modified
                }
            });
        }

        let create_body = json!({
            "parent": {
                "database_id": self.database_id
            },
            "properties": properties,
            "children": [
                {
                    "object": "block",
                    "type": "heading_2",
                    "heading_2": {
                        "rich_text": [
                            {
                                "type": "text",
                                "text": {
                                    "content": "OCR Extracted Text"
                                }
                            }
                        ]
                    }
                },
                {
                    "object": "block",
                    "type": "paragraph",
                    "paragraph": {
                        "rich_text": [
                            {
                                "type": "text",
                                "text": {
                                    "content": if content.len() > 2000 {
                                        &content[..2000]
                                    } else {
                                        content
                                    }
                                }
                            }
                        ]
                    }
                }
            ]
        });

        let response = self
            .client
            .post(format!("{}/pages", NOTION_API_BASE))
            .headers(self.headers())
            .json(&create_body)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await?;
            return Err(Error::Notion(format!(
                "Failed to create page: {} - {}",
                status, body
            )));
        }

        let response_json: serde_json::Value = response.json().await?;
        let page_id = response_json["id"]
            .as_str()
            .ok_or_else(|| Error::Notion("No page ID in response".to_string()))?
            .to_string();

        debug!("Created page with ID: {}", page_id);

        Ok(NotionPage {
            id: page_id,
            title: title.to_string(),
        })
    }

    pub async fn update_page(&self, page_id: &str, content: &str, tags: &[String]) -> Result<()> {
        debug!("Updating Notion page: {}", page_id);

        // Update tags if provided
        if !tags.is_empty() {
            debug!("Updating {} tags: {:?}", tags.len(), tags);
            let update_props = json!({
                "properties": {
                    "Tags": {
                        "multi_select": tags.iter().map(|tag| json!({"name": tag})).collect::<Vec<_>>()
                    }
                }
            });

            self.client
                .patch(format!("{}/pages/{}", NOTION_API_BASE, page_id))
                .headers(self.headers())
                .json(&update_props)
                .send()
                .await?;
        }

        let children_response = self
            .client
            .get(format!("{}/blocks/{}/children", NOTION_API_BASE, page_id))
            .headers(self.headers())
            .send()
            .await?;

        if children_response.status().is_success() {
            let blocks: BlockResponse = children_response.json().await?;

            for block in blocks.results {
                if let Some(block_id) = block["id"].as_str() {
                    self.client
                        .delete(format!("{}/blocks/{}", NOTION_API_BASE, block_id))
                        .headers(self.headers())
                        .send()
                        .await?;
                }
            }
        }

        let append_body = json!({
            "children": [
                {
                    "object": "block",
                    "type": "heading_2",
                    "heading_2": {
                        "rich_text": [
                            {
                                "type": "text",
                                "text": {
                                    "content": "OCR Extracted Text"
                                }
                            }
                        ]
                    }
                },
                {
                    "object": "block",
                    "type": "paragraph",
                    "paragraph": {
                        "rich_text": [
                            {
                                "type": "text",
                                "text": {
                                    "content": if content.len() > 2000 {
                                        &content[..2000]
                                    } else {
                                        content
                                    }
                                }
                            }
                        ]
                    }
                }
            ]
        });

        let response = self
            .client
            .patch(format!("{}/blocks/{}/children", NOTION_API_BASE, page_id))
            .headers(self.headers())
            .json(&append_body)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await?;
            return Err(Error::Notion(format!(
                "Failed to update page: {} - {}",
                status, body
            )));
        }

        debug!("Page updated successfully");
        Ok(())
    }

    pub async fn upload_pdf(&self, page_id: &str, pdf_path: &Path) -> Result<()> {
        debug!("Adding PDF reference to page: {}", page_id);

        let pdf_name = pdf_path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("notebook.pdf");

        // Add a paragraph with PDF reference
        self.add_pdf_text_reference(page_id, pdf_name).await?;

        // Also set the PDF Link property to the local path
        self.set_pdf_link(page_id, pdf_path).await?;

        Ok(())
    }

    async fn add_pdf_text_reference(&self, page_id: &str, pdf_name: &str) -> Result<()> {
        let append_body = json!({
            "children": [
                {
                    "object": "block",
                    "type": "paragraph",
                    "paragraph": {
                        "rich_text": [
                            {
                                "type": "text",
                                "text": {
                                    "content": format!("📎 PDF: {}", pdf_name)
                                }
                            }
                        ]
                    }
                }
            ]
        });

        self.client
            .patch(format!("{}/blocks/{}/children", NOTION_API_BASE, page_id))
            .headers(self.headers())
            .json(&append_body)
            .send()
            .await?;

        Ok(())
    }

    pub async fn set_pdf_link(&self, page_id: &str, pdf_path: &Path) -> Result<()> {
        // For local file path fallback (used when upload fails)
        let pdf_path_str = pdf_path.to_string_lossy().to_string();

        let update_body = json!({
            "properties": {
                "PDF Link": {
                    "url": format!("file://{}", pdf_path_str)
                }
            }
        });

        let response = self
            .client
            .patch(format!("{}/pages/{}", NOTION_API_BASE, page_id))
            .headers(self.headers())
            .json(&update_body)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await?;
            debug!("Failed to set PDF link (property may not exist): {} - {}", status, body);
        }

        Ok(())
    }

    pub async fn set_pdf_url(&self, page_id: &str, pdf_url: &str) -> Result<()> {
        let update_body = json!({
            "properties": {
                "PDF Link": {
                    "url": pdf_url
                }
            }
        });

        let response = self
            .client
            .patch(format!("{}/pages/{}", NOTION_API_BASE, page_id))
            .headers(self.headers())
            .json(&update_body)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await?;
            return Err(crate::error::Error::Notion(format!(
                "Failed to set PDF link: {} - {}",
                status, body
            )));
        }

        debug!("PDF Link property updated with URL: {}", pdf_url);
        Ok(())
    }

    /// Upload images directly to Notion storage (not external URLs)
    pub async fn add_uploaded_images(&self, page_id: &str, image_paths: &[(usize, &Path)]) -> Result<()> {
        if image_paths.is_empty() {
            return Ok(());
        }

        debug!("Uploading {} images to Notion: {}", image_paths.len(), page_id);

        let mut children = Vec::new();

        for (page_num, image_path) in image_paths {
            match self.upload_file_to_notion(image_path).await {
                Ok(file_id) => {
                    children.push(json!({
                        "object": "block",
                        "type": "image",
                        "image": {
                            "type": "file_upload",
                            "file_upload": {
                                "id": file_id
                            },
                            "caption": [
                                {
                                    "type": "text",
                                    "text": {
                                        "content": format!("Page {}", page_num)
                                    }
                                }
                            ]
                        }
                    }));
                }
                Err(e) => {
                    warn!("Failed to upload image {}: {}", page_num, e);
                }
            }
        }

        if children.is_empty() {
            return Ok(());
        }

        let append_body = json!({
            "children": children
        });

        let response = self
            .client
            .patch(&format!("{}/blocks/{}/children", NOTION_API_BASE, page_id))
            .header("Notion-Version", NOTION_API_VERSION)
            .bearer_auth(&self.token)
            .json(&append_body)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await?;
            return Err(Error::Io(std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("Failed to add uploaded images: {} - {}", status, body),
            )));
        }

        debug!("Added {} uploaded images to page", children.len());
        Ok(())
    }

    /// Upload a file directly to Notion and return its file ID
    async fn upload_file_to_notion(&self, file_path: &Path) -> Result<String> {
        let filename = file_path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("image.png");

        // Step 1: Create file upload
        let create_body = json!({
            "mode": "single_part",
            "filename": filename,
            "content_type": "image/png"
        });

        debug!("Creating file upload for: {}", filename);

        let create_response = self
            .client
            .post(&format!("{}/file_uploads", NOTION_API_BASE))
            .header("Notion-Version", "2025-09-03")  // File upload API requires newer version
            .bearer_auth(&self.token)
            .json(&create_body)
            .send()
            .await?;

        if !create_response.status().is_success() {
            let status = create_response.status();
            let body = create_response.text().await?;
            return Err(Error::Io(std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("Failed to create file upload: {} - {}", status, body),
            )));
        }

        let create_result: serde_json::Value = create_response.json().await?;
        let file_id = create_result["id"]
            .as_str()
            .ok_or_else(|| Error::Io(std::io::Error::new(
                std::io::ErrorKind::Other,
                "No file ID in create response",
            )))?
            .to_string();

        let upload_url = create_result["upload_url"]
            .as_str()
            .ok_or_else(|| Error::Io(std::io::Error::new(
                std::io::ErrorKind::Other,
                "No upload_url in create response",
            )))?;

        // Step 2: Upload file data
        debug!("Uploading file data to: {}", upload_url);

        let file_bytes = tokio::fs::read(file_path).await?;

        let file_part = reqwest::multipart::Part::bytes(file_bytes)
            .file_name(filename.to_string())
            .mime_str("image/png")?;

        let form = reqwest::multipart::Form::new()
            .part("file", file_part);

        let upload_response = self
            .client
            .post(upload_url)
            .header("Notion-Version", "2025-09-03")  // File upload API requires newer version
            .bearer_auth(&self.token)
            .multipart(form)
            .send()
            .await?;

        if !upload_response.status().is_success() {
            let status = upload_response.status();
            let body = upload_response.text().await?;
            return Err(Error::Io(std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("Failed to upload file data: {} - {}", status, body),
            )));
        }

        debug!("File uploaded successfully: {}", file_id);

        Ok(file_id)
    }
}
