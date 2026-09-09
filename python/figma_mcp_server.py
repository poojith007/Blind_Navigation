#!/usr/bin/env python3
"""
Figma Model Context Protocol (MCP) Server
Runs over standard I/O (stdio) using the official JSON-RPC 2.0 MCP protocol.
Exposes Figma REST API endpoints as accessible MCP tools.
"""

import sys
import os
import json
import urllib.request
import urllib.error
import urllib.parse
import re

FIGMA_TOKEN = os.environ.get("FIGMA_ACCESS_TOKEN", "figd_tst295SiDnrzHH1ZolSTsUykGADfVkg6gJWjJ5dH")
BASE_URL = "https://api.figma.com/v1"

def _api_request(endpoint: str, params: dict = None) -> dict:
    url = f"{BASE_URL}/{endpoint}"
    if params:
        query_str = urllib.parse.urlencode(params)
        url = f"{url}?{query_str}"
    
    headers = {
        "X-Figma-Token": FIGMA_TOKEN,
        "User-Agent": "Antigravity-Figma-MCP/1.0"
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
    match = re.search(r"figma\.com/(?:file|design)/([a-zA-Z0-9]+)", input_str)
    if match:
        return match.group(1)
    return input_str.strip()

TOOLS = [
    {
        "name": "figma_get_file",
        "description": "Retrieve the JSON document tree of a Figma file, including canvas pages, frames, and layers.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "file_key": {
                    "type": "string",
                    "description": "The Figma file key or complete Figma file URL."
                },
                "depth": {
                    "type": "integer",
                    "description": "Depth of child nodes to traverse (default: 2 to keep response size manageable).",
                    "default": 2
                }
            },
            "required": ["file_key"]
        }
    },
    {
        "name": "figma_get_nodes",
        "description": "Retrieve specific node details (components, frames, vector layers) by their Figma Node IDs.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "file_key": {
                    "type": "string",
                    "description": "The Figma file key or complete Figma file URL."
                },
                "node_ids": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "List of node IDs to inspect (e.g. ['0:1', '12:34'])."
                }
            },
            "required": ["file_key", "node_ids"]
        }
    },
    {
        "name": "figma_get_images",
        "description": "Render and get direct image URLs (PNG, SVG, or JPG) for specific frames or components in a Figma file.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "file_key": {
                    "type": "string",
                    "description": "The Figma file key or complete Figma file URL."
                },
                "node_ids": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "List of node IDs to render as images."
                },
                "format": {
                    "type": "string",
                    "enum": ["png", "jpg", "svg", "pdf"],
                    "default": "png",
                    "description": "Image format to export."
                },
                "scale": {
                    "type": "number",
                    "default": 2.0,
                    "description": "Image export scale factor (1.0 to 4.0)."
                }
            },
            "required": ["file_key", "node_ids"]
        }
    },
    {
        "name": "figma_get_styles",
        "description": "Retrieve design tokens, typography styles, color palettes, and effects published in a Figma file.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "file_key": {
                    "type": "string",
                    "description": "The Figma file key or complete Figma file URL."
                }
            },
            "required": ["file_key"]
        }
    },
    {
        "name": "figma_get_comments",
        "description": "Read design feedback and comments on a Figma file.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "file_key": {
                    "type": "string",
                    "description": "The Figma file key or complete Figma file URL."
                }
            },
            "required": ["file_key"]
        }
    }
]

def handle_tool_call(name: str, arguments: dict) -> dict:
    raw_key = arguments.get("file_key", "")
    file_key = extract_file_key(raw_key)

    if name == "figma_get_file":
        depth = arguments.get("depth", 2)
        data = _api_request(f"files/{file_key}", {"depth": depth})
        return {"content": [{"type": "text", "text": json.dumps(data, indent=2)}]}
    
    elif name == "figma_get_nodes":
        node_ids = arguments.get("node_ids", [])
        data = _api_request(f"files/{file_key}/nodes", {"ids": ",".join(node_ids)})
        return {"content": [{"type": "text", "text": json.dumps(data, indent=2)}]}
    
    elif name == "figma_get_images":
        node_ids = arguments.get("node_ids", [])
        fmt = arguments.get("format", "png")
        scale = arguments.get("scale", 2.0)
        data = _api_request(f"images/{file_key}", {
            "ids": ",".join(node_ids),
            "format": fmt,
            "scale": scale
        })
        return {"content": [{"type": "text", "text": json.dumps(data, indent=2)}]}
    
    elif name == "figma_get_styles":
        data = _api_request(f"files/{file_key}/styles")
        return {"content": [{"type": "text", "text": json.dumps(data, indent=2)}]}
    
    elif name == "figma_get_comments":
        data = _api_request(f"files/{file_key}/comments")
        return {"content": [{"type": "text", "text": json.dumps(data, indent=2)}]}
    
    else:
        return {"isError": True, "content": [{"type": "text", "text": f"Unknown tool: {name}"}]}

def send_response(response: dict):
    line = json.dumps(response)
    sys.stdout.write(line + "\n")
    sys.stdout.flush()

def main():
    while True:
        try:
            line = sys.stdin.readline()
            if not line:
                break
            
            line = line.strip()
            if not line:
                continue
            
            req = json.loads(line)
            req_id = req.get("id")
            method = req.get("method")
            params = req.get("params", {})

            if method == "initialize":
                send_response({
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "protocolVersion": "2024-11-05",
                        "capabilities": {
                            "tools": {}
                        },
                        "serverInfo": {
                            "name": "figma-mcp",
                            "version": "1.0.0"
                        }
                    }
                })
            elif method in ["notifications/initialized", "initialized"]:
                continue
            elif method == "tools/list":
                send_response({
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "tools": TOOLS
                    }
                })
            elif method == "tools/call":
                tool_name = params.get("name")
                tool_args = params.get("arguments", {})
                result = handle_tool_call(tool_name, tool_args)
                send_response({
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": result
                })
            elif method == "ping":
                send_response({
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {}
                })
            else:
                if req_id is not None:
                    send_response({
                        "jsonrpc": "2.0",
                        "id": req_id,
                        "error": {
                            "code": -32601,
                            "message": f"Method '{method}' not found"
                        }
                    })
        except Exception as e:
            sys.stderr.write(f"Error handling request: {e}\n")
            sys.stderr.flush()

if __name__ == "__main__":
    main()
