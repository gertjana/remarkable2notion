use crate::error::{Error, Result};
use crate::notion::NotebookMetadata;
use serde::Deserialize;
use std::path::{Path, PathBuf};
use std::process::Command;
use tracing::{debug, info};

#[derive(Debug, Clone, Deserialize)]
pub struct Notebook {
    pub name: String,
    pub path: String,
    #[allow(dead_code)]
    pub id: String,
    pub metadata: NotebookMetadata,
    pub tags: Vec<String>,
    pub is_deleted: bool,
}

#[derive(Debug, Deserialize)]
struct Tag {
    name: String,
    #[allow(dead_code)]
    timestamp: i64,
}

#[derive(Debug, Deserialize)]
struct ContentFile {
    #[serde(default)]
    tags: Vec<Tag>,
}

#[derive(Debug, Deserialize)]
struct MetadataFile {
    #[serde(rename = "visibleName")]
    visible_name: String,
    parent: Option<String>,
    #[serde(rename = "createdTime")]
    created_time: Option<String>,
    #[serde(rename = "lastModified")]
    last_modified: Option<String>,
}

pub struct RemarkableClient {
    backup_dir: PathBuf,
    password: Option<String>,
}

impl RemarkableClient {
    pub async fn new(backup_dir: Option<PathBuf>, password: Option<String>) -> Result<Self> {
        let backup_dir = backup_dir.unwrap_or_else(|| {
            std::env::current_dir()
                .expect("Failed to get current directory")
                .join("remarkable_backup")
        });

        // Create backup directory if it doesn't exist
        std::fs::create_dir_all(&backup_dir)?;

        Ok(Self {
            backup_dir,
            password,
        })
    }

    pub async fn check_installation(&self) -> Result<()> {
        debug!("Checking RemarkableSync installation");

        let output = Command::new("RemarkableSync")
            .arg("--version")
            .output()
            .map_err(|e| {
                Error::Remarkable(format!(
                    "RemarkableSync not found: {}. Install with: brew install remarkablesync",
                    e
                ))
            })?;

        if !output.status.success() {
            return Err(Error::Remarkable(
                "RemarkableSync not working properly".to_string(),
            ));
        }

        let version = String::from_utf8_lossy(&output.stdout);
        debug!("RemarkableSync found: {}", version.trim());
        Ok(())
    }

    pub async fn list_notebooks(&self) -> Result<Vec<Notebook>> {
        info!("Syncing from reMarkable (USB)...");
        debug!("⚠️  Make sure your ReMarkable tablet is connected via USB!");

        // Run RemarkableSync to backup and convert
        let mut cmd = Command::new("RemarkableSync");
        cmd.arg("sync")
            .arg("--backup-dir")
            .arg(&self.backup_dir)
            .arg("--skip-templates");  // Skip templates to avoid errors

        if let Some(ref password) = self.password {
            cmd.arg("--password").arg(password);
        }

        let output = cmd.output().map_err(|e| {
            Error::Remarkable(format!("Failed to run RemarkableSync: {}", e))
        })?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            let stdout = String::from_utf8_lossy(&output.stdout);

            // Check if it's just a template error but files synced successfully
            if stdout.contains("All files are up to date") || stdout.contains("Backup completed") {
                debug!("Files synced successfully (template error ignored)");
            } else {
                return Err(Error::Remarkable(format!(
                    "RemarkableSync failed: {}. Make sure tablet is connected via USB.",
                    stderr
                )));
            }
        }

        // Find all converted PDFs in the backup directory
        // RemarkableSync uses capital 'PDF' directory
        let pdfs_dir = self.backup_dir.join("PDF");
        if !pdfs_dir.exists() {
            debug!("No PDF directory found yet - no notebooks synced");
            return Ok(Vec::new());
        }

        let mut notebooks = Vec::new();
        self.scan_pdfs_recursive(&pdfs_dir, "", &mut notebooks)?;

        debug!("Found {} notebooks", notebooks.len());
        Ok(notebooks)
    }

    fn scan_pdfs_recursive(
        &self,
        dir: &Path,
        relative_path: &str,
        notebooks: &mut Vec<Notebook>,
    ) -> Result<()> {
        for entry in std::fs::read_dir(dir)? {
            let entry = entry?;
            let path = entry.path();

            if path.is_dir() {
                let folder_name = path.file_name().unwrap().to_string_lossy();
                let new_path = if relative_path.is_empty() {
                    folder_name.to_string()
                } else {
                    format!("{}/{}", relative_path, folder_name)
                };
                self.scan_pdfs_recursive(&path, &new_path, notebooks)?;
            } else if path.extension().and_then(|s| s.to_str()) == Some("pdf") {
                let name = path
                    .file_stem()
                    .unwrap()
                    .to_string_lossy()
                    .to_string();

                let full_name = if relative_path.is_empty() {
                    name.clone()
                } else {
                    format!("{}/{}", relative_path, name)
                };

                // Read timestamps, tags, and deleted status from the .metadata and .content files
                let (created_time, modified_time, tags, is_deleted) = self.read_metadata_and_tags(&name)?;

                notebooks.push(Notebook {
                    name,
                    path: full_name.clone(),
                    id: full_name.clone(),
                    metadata: NotebookMetadata {
                        created_time,
                        modified_time,
                        folder_path: relative_path.to_string(),
                    },
                    tags,
                    is_deleted,
                });
            }
        }

        Ok(())
    }

    fn read_metadata_and_tags(&self, notebook_name: &str) -> Result<(Option<String>, Option<String>, Vec<String>, bool)> {
        // The .content and .metadata files are in the Notebooks directory
        // They're named with UUIDs, so we need to:
        // 1. Find the .metadata file with matching visibleName
        // 2. Use the same UUID to read the .content file for tags
        // 3. Read timestamps from the .metadata file
        // 4. Check if parent is "trash" to mark as deleted
        let notebooks_dir = self.backup_dir.join("Notebooks");

        debug!("Looking for metadata and tags for notebook: {}", notebook_name);

        // Find the UUID by matching notebook name in .metadata files
        for entry in std::fs::read_dir(&notebooks_dir)? {
            let entry = entry?;
            let path = entry.path();

            if path.extension().and_then(|s| s.to_str()) == Some("metadata") {
                if let Ok(metadata_content) = std::fs::read_to_string(&path) {
                    if let Ok(metadata) = serde_json::from_str::<MetadataFile>(&metadata_content) {
                        if metadata.visible_name == notebook_name {
                            // Check if notebook is in trash
                            let is_deleted = metadata.parent.as_ref().map(|p| p == "trash").unwrap_or(false);
                            if is_deleted {
                                debug!("Notebook '{}' is in trash", notebook_name);
                            }

                            // Extract UUID from filename (remove .metadata extension)
                            let uuid = path.file_stem()
                                .and_then(|s| s.to_str())
                                .map(|s| s.to_string());

                            debug!("Found UUID {:?} for notebook {}", uuid, notebook_name);

                            // Convert timestamps from milliseconds to RFC3339
                            let created_time = metadata.created_time.and_then(|ts| {
                                ts.parse::<i64>().ok().and_then(|millis| {
                                    chrono::DateTime::from_timestamp_millis(millis)
                                        .map(|dt| dt.to_rfc3339_opts(chrono::SecondsFormat::Secs, true))
                                })
                            });

                            let modified_time = metadata.last_modified.and_then(|ts| {
                                ts.parse::<i64>().ok().and_then(|millis| {
                                    chrono::DateTime::from_timestamp_millis(millis)
                                        .map(|dt| dt.to_rfc3339_opts(chrono::SecondsFormat::Secs, true))
                                })
                            });

                            // Read tags from .content file with matching UUID
                            let mut tags = Vec::new();
                            if let Some(ref uuid_str) = uuid {
                                let content_path = notebooks_dir.join(format!("{}.content", uuid_str));
                                if content_path.exists() {
                                    if let Ok(content) = std::fs::read_to_string(&content_path) {
                                        if let Ok(content_data) = serde_json::from_str::<ContentFile>(&content) {
                                            if !content_data.tags.is_empty() {
                                                tags = content_data.tags
                                                    .iter()
                                                    .map(|tag| tag.name.clone())
                                                    .collect();
                                                debug!("Found {} tags for {}: {:?}", tags.len(), notebook_name, tags);
                                            }
                                        }
                                    }
                                }
                            }

                            return Ok((created_time, modified_time, tags, is_deleted));
                        }
                    }
                }
            }
        }

        debug!("No metadata found for {}", notebook_name);
        Ok((None, None, Vec::new(), false))
    }



    pub async fn download_notebook(&self, notebook: &Notebook, output_dir: &Path) -> Result<PathBuf> {
        debug!("Copying notebook PDF: {}", notebook.name);

        // The PDF is already in the backup directory (capital PDF), just copy it
        let source_path = self.backup_dir.join("PDF").join(format!("{}.pdf", notebook.path));

        if !source_path.exists() {
            return Err(Error::Remarkable(format!(
                "PDF not found at {:?}. Notebook might not have been synced/converted yet.",
                source_path
            )));
        }

        let output_path = output_dir.join(format!("{}.pdf", notebook.name));
        std::fs::copy(&source_path, &output_path)?;

        debug!("Copied to: {:?}", output_path);
        Ok(output_path)
    }
}
