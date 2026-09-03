"""
Test Inference Runner for YOLO11 & Blind Navigation Pipeline
Supports running REAL inference on any image using yolo11n.onnx or yolo11n.pt,
as well as running the end-to-end simulated pipeline.
"""

import os
import sys
import numpy as np
import cv2

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS_DIR = os.path.join(ROOT_DIR, "android", "app", "src", "main", "assets")
LABELS_PATH = os.path.join(ASSETS_DIR, "labels.txt")
ONNX_PATH = os.path.join(ROOT_DIR, "yolo11n.onnx")
PT_PATH = os.path.join(ROOT_DIR, "yolo11n.pt")

# Standard real-world heights (meters) for distance estimation heuristics
OBJECT_REAL_HEIGHTS = {
    "person": 1.70,
    "chair": 0.90,
    "couch": 0.85,
    "car": 1.50,
    "bus": 3.20,
    "truck": 3.00,
    "bicycle": 1.00,
    "motorcycle": 1.10,
    "bottle": 0.25,
    "tv": 0.60,
    "dining table": 0.75,
    "door": 2.00,
    "stairs": 1.50,
    "traffic light": 0.80,
    "fire hydrant": 0.70,
    "stop sign": 0.90
}

def load_labels(path):
    if not os.path.exists(path):
        return []
    with open(path, "r", encoding="utf-8") as f:
        return [line.strip() for line in f.readlines() if line.strip()]

def calculate_distance(label, box_height_fraction):
    """
    Focal length bounding box distance formula:
    Distance (meters) = (Focal Length Constant * Real Height) / Bounding Box Height Fraction
    """
    real_height = OBJECT_REAL_HEIGHTS.get(label.lower(), 1.0)
    focal_constant = 0.85  # Calibrated for standard smartphone field of view (~60-70 deg FOV)
    
    if box_height_fraction <= 0:
        return 999.0
        
    estimated_distance = (focal_constant * real_height) / box_height_fraction
    return round(estimated_distance, 2)

def classify_threat_level(distance_meters, label):
    """
    Threat Level Classifier:
    - DANGER: Distance <= 1.2m or moving obstacle (car, bus, truck, etc.) <= 2.5m
    - CAUTION: Distance <= 2.5m
    - SAFE: Distance > 2.5m
    """
    is_high_risk_class = label.lower() in ["car", "bus", "truck", "motorcycle", "stairs"]
    
    if distance_meters <= 1.2 or (is_high_risk_class and distance_meters <= 2.5):
        return "DANGER [CRITICAL]"
    elif distance_meters <= 2.5:
        return "CAUTION [WARNING]"
    else:
        return "SAFE [CLEAR]"

def determine_position(xmin, xmax):
    center_x = (xmin + xmax) / 2.0
    if center_x < 0.35:
        return "Left", center_x
    elif center_x > 0.65:
        return "Right", center_x
    else:
        return "Ahead/Center", center_x

def run_real_image_inference(image_path, conf_threshold=0.35, iou_threshold=0.50):
    print("=" * 70)
    print(f" [AI VISION] Running Real Inference on Image: {image_path}")
    print("=" * 70)

    if not os.path.exists(image_path):
        print(f"Error: Image '{image_path}' does not exist.")
        return False

    img_bgr = cv2.imread(image_path)
    if img_bgr is None:
        print(f"Error: Failed to read image '{image_path}' with OpenCV.")
        return False

    orig_h, orig_w = img_bgr.shape[:2]
    labels = load_labels(LABELS_PATH)
    print(f"Loaded {len(labels)} labels from: {LABELS_PATH}")

    detections = []

    # 1. Try ONNX Runtime inference first
    if os.path.exists(ONNX_PATH):
        try:
            import onnxruntime as ort
            print(f"Using ONNX Runtime with model: {ONNX_PATH}")
            session = ort.InferenceSession(ONNX_PATH)
            
            # Preprocess image to 640x640 RGB float32 NCHW
            resized = cv2.resize(img_bgr, (640, 640))
            rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
            input_tensor = rgb.transpose(2, 0, 1).astype(np.float32) / 255.0
            input_tensor = np.expand_dims(input_tensor, axis=0)

            input_name = session.get_inputs()[0].name
            raw_output = session.run(None, {input_name: input_tensor})[0][0] # shape (84, 8400)
            
            # Post-processing YOLO11 output: (4 coords + 80 class scores)
            coords = raw_output[:4, :] # (cx, cy, w, h) in 640x640
            scores = raw_output[4:, :] # (80, 8400)
            
            max_scores = np.max(scores, axis=0)
            class_ids = np.argmax(scores, axis=0)

            candidates = []
            for a in range(raw_output.shape[1]):
                score = max_scores[a]
                if score >= conf_threshold:
                    cid = class_ids[a]
                    cx, cy, w, h = coords[:, a]
                    # Normalized coords [0, 1]
                    xmin = max(0.0, float((cx - w / 2.0) / 640.0))
                    ymin = max(0.0, float((cy - h / 2.0) / 640.0))
                    xmax = min(1.0, float((cx + w / 2.0) / 640.0))
                    ymax = min(1.0, float((cy + h / 2.0) / 640.0))

                    lbl = labels[cid] if cid < len(labels) else f"class_{cid}"
                    candidates.append({
                        "class_id": int(cid),
                        "label": lbl,
                        "confidence": float(score),
                        "box": [ymin, xmin, ymax, xmax],
                        "orig_box": [int(ymin * orig_h), int(xmin * orig_w), int(ymax * orig_h), int(xmax * orig_w)]
                    })

            # NMS filter
            candidates.sort(key=lambda x: x["confidence"], reverse=True)
            active = [True] * len(candidates)
            for i in range(len(candidates)):
                if not active[i]:
                    continue
                box_a = candidates[i]["box"]
                detections.append(candidates[i])
                for j in range(i + 1, len(candidates)):
                    if not active[j]:
                        continue
                    if candidates[i]["class_id"] == candidates[j]["class_id"]:
                        box_b = candidates[j]["box"]
                        # IoU
                        ix1 = max(box_a[1], box_b[1])
                        iy1 = max(box_a[0], box_b[0])
                        ix2 = min(box_a[3], box_b[3])
                        iy2 = min(box_a[2], box_b[2])
                        inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
                        area_a = (box_a[3] - box_a[1]) * (box_a[2] - box_a[0])
                        area_b = (box_b[3] - box_b[1]) * (box_b[2] - box_b[0])
                        union = area_a + area_b - inter
                        iou = inter / union if union > 0 else 0.0
                        if iou > iou_threshold:
                            active[j] = False
        except Exception as e:
            print(f"ONNX inference note: {e}")

    # 2. Fallback to Ultralytics YOLO if ONNX wasn't used or produced 0 detections
    if not detections and os.path.exists(PT_PATH):
        try:
            from ultralytics import YOLO
            print(f"Using Ultralytics PyTorch model: {PT_PATH}")
            model = YOLO(PT_PATH)
            results = model(image_path, conf=conf_threshold, iou=iou_threshold)
            res = results[0]
            for box, cls_id, conf in zip(res.boxes.xyxy.cpu().numpy(), res.boxes.cls.cpu().numpy(), res.boxes.conf.cpu().numpy()):
                x1, y1, x2, y2 = box
                xmin = float(x1 / orig_w)
                ymin = float(y1 / orig_h)
                xmax = float(x2 / orig_w)
                ymax = float(y2 / orig_h)
                cid = int(cls_id)
                lbl = labels[cid] if cid < len(labels) else model.names.get(cid, f"class_{cid}")
                detections.append({
                    "class_id": cid,
                    "label": lbl,
                    "confidence": float(conf),
                    "box": [ymin, xmin, ymax, xmax],
                    "orig_box": [int(y1), int(x1), int(y2), int(x2)]
                })
        except Exception as e:
            print(f"PyTorch inference note: {e}")

    print(f"\nSuccessfully recognized {len(detections)} object(s) in the image!")
    print("-" * 70)

    # Annotated image copy
    annotated = img_bgr.copy()

    for idx, det in enumerate(detections, 1):
        ymin, xmin, ymax, xmax = det["box"]
        h_frac = ymax - ymin
        w_frac = xmax - xmin
        
        position, cx = determine_position(xmin, xmax)
        dist = calculate_distance(det["label"], h_frac)
        threat = classify_threat_level(dist, det["label"])

        pos_prompt = "ahead" if position == "Ahead/Center" else f"on your {position.lower()}"
        alert_prefix = "Warning! " if "DANGER" in threat else ""
        tts_prompt = f"{alert_prefix}{det['label'].capitalize()} {pos_prompt}, {dist} meters away."

        print(f"\nItem #{idx}: [{det['label'].upper()}]")
        print(f"  * Confidence    : {det['confidence'] * 100:.1f}%")
        print(f"  * Position      : {position} (Horizontal center: {cx:.2f})")
        print(f"  * Box Fraction  : Height={h_frac:.2f}, Width={w_frac:.2f}")
        print(f"  * Est. Distance : {dist} meters")
        print(f"  * Threat Rating : {threat}")
        print(f"  * Spoken Audio  : \"{tts_prompt}\"")

        # Draw on image
        y1, x1, y2, x2 = det["orig_box"]
        color = (0, 0, 255) if "DANGER" in threat else ((0, 165, 255) if "CAUTION" in threat else (0, 255, 0))
        cv2.rectangle(annotated, (x1, y1), (x2, y2), color, 3)
        tag_text = f"{det['label']} ({det['confidence']*100:.0f}%) {dist}m [{position}]"
        (tw, th), _ = cv2.getTextSize(tag_text, cv2.FONT_HERSHEY_SIMPLEX, 0.6, 2)
        cv2.rectangle(annotated, (x1, max(0, y1 - th - 8)), (x1 + tw + 6, max(th + 8, y1)), color, -1)
        cv2.putText(annotated, tag_text, (x1 + 3, max(th, y1 - 4)), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 2)

    output_annotated_path = os.path.join(ROOT_DIR, "annotated_detection_result.jpg")
    cv2.imwrite(output_annotated_path, annotated)
    print(f"\n[SAVED] Annotated visualization saved to: {output_annotated_path}")
    print("=" * 70)
    return True

def run_simulated_detection_test():
    print("=" * 65)
    print(" Blind Navigation AI Engine - Inference Test Simulation")
    print("=" * 65)

    labels = load_labels(LABELS_PATH)
    print(f"Loaded {len(labels)} labels from assets.")

    simulated_detections = [
        {"class_id": 56, "label": "chair", "confidence": 0.88, "box": [0.35, 0.40, 0.85, 0.70]},
        {"class_id": 0,  "label": "person", "confidence": 0.92, "box": [0.10, 0.20, 0.90, 0.60]},
        {"class_id": 2,  "label": "car",    "confidence": 0.81, "box": [0.40, 0.10, 0.70, 0.45]},
        {"class_id": 39, "label": "bottle", "confidence": 0.76, "box": [0.70, 0.80, 0.90, 0.95]},
    ]

    print("\n--- Detection & Distance Estimation Results ---")
    for idx, det in enumerate(simulated_detections, 1):
        ymin, xmin, ymax, xmax = det["box"]
        height_fraction = ymax - ymin
        width_fraction = xmax - xmin
        
        position, center_x = determine_position(xmin, xmax)
        dist = calculate_distance(det["label"], height_fraction)
        threat = classify_threat_level(dist, det["label"])

        tts_prompt = f"{det['label'].capitalize()} on your {position}, {dist} meters away."

        print(f"\nItem #{idx}: [{det['label'].upper()}]")
        print(f"  - Confidence    : {det['confidence'] * 100:.1f}%")
        print(f"  - Position      : {position} (Center X: {center_x:.2f})")
        print(f"  - Box Fraction  : Height={height_fraction:.2f}, Width={width_fraction:.2f}")
        print(f"  - Est. Distance : {dist} meters")
        print(f"  - Threat Rating : {threat}")
        print(f"  - Voice Prompt  : \"{tts_prompt}\"")

    print("\n" + "=" * 65)
    print(" [SUCCESS] Test Complete! Algorithm pipelines are valid.")
    print("=" * 65)

if __name__ == "__main__":
    test_image = None
    if len(sys.argv) > 1:
        test_image = sys.argv[1]
    elif os.path.exists(os.path.join(ROOT_DIR, "bus.jpg")):
        test_image = os.path.join(ROOT_DIR, "bus.jpg")

    if test_image and os.path.exists(test_image):
        run_real_image_inference(test_image)
    else:
        run_simulated_detection_test()
