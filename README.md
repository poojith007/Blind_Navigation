# Blind Navigation — AI-Powered Assistive Navigation

An Android-based assistive navigation project designed to help blind and visually impaired users navigate their surroundings through voice interaction, location awareness, and camera-based obstacle detection.

The project combines an Android application with Python-based computer vision components to explore accessible, hands-free navigation and environmental awareness.

## Overview

Blind Navigation aims to make navigation more accessible by providing spoken interaction and environmental feedback. The system is designed to help users understand their surroundings, request navigation assistance, and interact with the application without depending entirely on a visual interface.

## Key Features

- **Voice Assistant:** Interact with the application using natural-language voice commands.
- **Voice-Guided Navigation:** Request destinations and receive navigation-related assistance.
- **Obstacle Detection:** Use camera-based object detection to identify relevant objects in the environment.
- **Environmental Awareness:** Provide audio feedback to help users understand nearby surroundings.
- **Text-to-Speech Feedback:** Communicate important information through spoken responses.
- **Emergency Assistance:** Support emergency workflows involving a configured guardian or trusted contact.
- **Accessibility-Focused Design:** Prioritize audio interaction and reduce dependence on visual controls.

*Feature availability depends on the current implementation, configuration, and connected services.*

## Technology Stack

- **Android:** Kotlin, Android SDK, Gradle
- **Computer Vision:** Python and ONNX/PyTorch model formats
- **Voice Interaction:** Android SpeechRecognizer and TextToSpeech
- **Navigation:** Location services and route-related integrations
- **Machine Learning:** YOLO-based object detection models

## Repository Structure

```text
Blind_Navigation/
├── android/                 # Android application
│   ├── app/                 # Application source and resources
│   ├── gradle/              # Gradle wrapper files
│   └── build.gradle.kts     # Android build configuration
├── python/                  # Python utilities and model testing
│   ├── export_models.py
│   ├── requirements.txt
│   ├── run_full_system_simulation.py
│   └── test_inference.py
├── diagrams/                # Project diagrams
├── yolo11n.onnx             # ONNX model file
├── yolo11n.pt               # PyTorch model file
└── README.md
```

## Getting Started

### Prerequisites

- Android Studio
- Android SDK and a compatible JDK
- A physical Android device or emulator
- Python installed if you intend to run the Python utilities
- Git

### 1. Clone the Repository

```bash
git clone https://github.com/poojith007/Blind_Navigation.git
cd Blind_Navigation
```

### 2. Run the Android Application

1. Open Android Studio.
2. Select **Open** and choose the repository's `android` directory.
3. Allow Gradle to synchronize the project.
4. Connect an Android device or start an emulator.
5. Grant the permissions required by the application.
6. Build and run the `app` configuration.

### 3. Set Up the Python Components

Open a terminal in the `python` directory:

```bash
cd python
python -m venv .venv
```

Activate the virtual environment on Windows:

```bash
.venv\Scripts\activate
```

Install the listed dependencies:

```bash
python -m pip install -r requirements.txt
```

Consult the individual Python scripts and configuration before running model inference or the full-system simulation. Additional model files, services, or configuration may be required.

## Testing

Test the application on a device before relying on it for navigation.

Recommended checks include:

- Voice recognition and command processing
- Spoken responses and listening recovery
- Destination handling and location permissions
- Object detection and environmental audio feedback
- Emergency and guardian-contact workflows
- Python model inference and error handling

Record crashes, incorrect detections, and failed commands during testing.

## Safety Notice

Blind Navigation is a development project and should not be treated as a certified mobility aid. Object detection and navigation systems can fail because of lighting, camera position, GPS inaccuracies, network availability, or model limitations.

Do not rely on this application as the sole means of avoiding obstacles or navigating traffic. Test in controlled environments and retain established mobility aids and appropriate assistance.

## Future Improvements

- Improve voice recognition reliability and command handling.
- Reduce repeated or unnecessary audio warnings.
- Improve obstacle prioritization and environmental awareness.
- Strengthen offline behavior and error recovery.
- Expand accessibility and real-world usability testing.

## Contributing

Contributions, bug reports, and suggestions are welcome. Please describe the issue, steps to reproduce it, expected behavior, and relevant logs when reporting a bug.

## License

Check the repository's licensing terms before redistributing or modifying the project for public release.

## Author

**Poojith S Menon**

- GitHub: [@poojith007](https://github.com/poojith007)
- Project: [Blind_Navigation](https://github.com/poojith007/Blind_Navigation)
