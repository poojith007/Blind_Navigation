#!/usr/bin/env python3
"""
Figma CLI & Inspector Tool for Antigravity
Enables fetching files, frames, nodes, styles, and image exports directly from Figma.
"""

import sys
import os
import json
import urllib.request
import urllib.error
import re

FIGMA_TOKEN = os.environ.get("FIGMA_ACCESS_TOKEN", "figd_tst295SiDnrzHH1ZolSTsUykGADfVkg6gJWjJ5dH")
BASE_URL = "https://api.figma.com/v1"

def _api_request(endpoint: str, params: dict = None) -> dict:
    url = f"{BASE_URL}/{endpoint}"
    if params:
        query_str = "&".join(f"{k}={urllib.parse.quote(str(v))}" for k, v in params.items())
        url = f"{url}?{query_str}"
    
    headers = {
        "X-Figma-Token": FIGMA_TOKEN,
        "User-Agent": "Antigravity-Figma-Client/1.0"
    }
    
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8")
        try:
            err_json = json.loads(error_body)
            return {"error": True, "status": e.code, "message": err_json.get("message", error_body)}
        except Exception:
            return {"error": True, "status": e.code, "message": error_body}
    except Exception as e:
        return {"error": True, "message": str(e)}

def extract_file_key(input_str: str) -> str:
    """Extracts file key from Figma URL or returns key as is."""
    match = re.search(r"figma\.com/(?:file|design)/([a-zA-Z0-9]+)", input_str)
    if match:
        return match.group(1)
    return input_str.strip()

def get_me() -> dict:
    return _api_request("me")

def get_file(file_key: str, depth: int = 2) -> dict:
    return _api_request(f"files/{file_key}", {"depth": depth})

def get_nodes(file_key: str, node_ids: list) -> dict:
    return _api_request(f"files/{file_key}/nodes", {"ids": ",".join(node_ids)})

def get_images(file_key: str, node_ids: list, format: str = "png", scale: float = 2.0) -> dict:
    return _api_request(f"images/{file_key}", {
        "ids": ",".join(node_ids),
        "format": format,
        "scale": scale
    })

def get_file_styles(file_key: str) -> dict:
    return _api_request(f"files/{file_key}/styles")

def get_file_components(file_key: str) -> dict:
    return _api_request(f"files/{file_key}/components")

def get_comments(file_key: str) -> dict:
    return _api_request(f"files/{file_key}/comments")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python figma_tool.py [me|file|nodes|images|styles] <file_key_or_url> [args...]")
        sys.exit(1)
    
    cmd = sys.argv[1].lower()
    if cmd == "me":
        res = get_me()
        print(json.dumps(res, indent=2))
    elif cmd in ["file", "get_file"] and len(sys.argv) >= 3:
        key = extract_file_key(sys.argv[2])
        depth = int(sys.argv[3]) if len(sys.argv) > 3 else 2
        res = get_file(key, depth)
        print(json.dumps(res, indent=2))
    elif cmd in ["nodes", "node"] and len(sys.argv) >= 4:
        key = extract_file_key(sys.argv[2])
        ids = sys.argv[3].split(",")
        res = get_nodes(key, ids)
        print(json.dumps(res, indent=2))
    elif cmd in ["images", "export"] and len(sys.argv) >= 4:
        key = extract_file_key(sys.argv[2])
        ids = sys.argv[3].split(",")
        fmt = sys.argv[4] if len(sys.argv) > 4 else "png"
        res = get_images(key, ids, fmt)
        print(json.dumps(res, indent=2))
    elif cmd == "styles" and len(sys.argv) >= 3:
        key = extract_file_key(sys.argv[2])
        res = get_file_styles(key)
        print(json.dumps(res, indent=2))
    else:
        print(f"Unknown command or insufficient arguments: {cmd}")
