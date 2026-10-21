resource "google_cloud_run_service" "app" {
  template {
    spec {
      containers {
        env { name = "DB_URL"       value = var.db_url }
        env { name = "SECONDARY_ID" value = var.client_id }
      }
    }
  }
}
