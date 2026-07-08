from __future__ import annotations

import os
from pathlib import Path

from huggingface_hub import upload_folder

ROOT = Path(__file__).resolve().parents[1]
SPACE_DIR = ROOT / "deploy" / "huggingface-space"
REPO_ID = os.environ.get("HF_SPACE_REPO", "Oliverrrnh/firesky-v2-inference")
TOKEN = os.environ.get("HF_TOKEN")

if not TOKEN:
    raise SystemExit("Set HF_TOKEN before running this script.")

upload_folder(
    repo_id=REPO_ID,
    repo_type="space",
    folder_path=str(SPACE_DIR),
    commit_message="Deploy FireSky v2 inference service",
    ignore_patterns=["**/__pycache__/**", "**/*.pyc"],
    token=TOKEN,
)

print(f"Uploaded {SPACE_DIR} to {REPO_ID}")
