# Canny Edge Detector - Android App

<p align="center">
  <img src="app/src/main/res/mipmap-xxhdpi/ic_launcher.webp" alt="App Icon" width="120"/>
</p>

An educational Android application that demonstrates each step of the **Canny Edge Detection** algorithm using OpenCV, with future plans for improvement.

---

## Features

### Current Implementation
- Step-by-step visualization of the Canny Edge Detection process:
  - Grayscale conversion  
  - Gaussian blur filtering  
  - Gradient calculation (Sobel operator)  
  - Non-maximum suppression  
  - Double thresholding  
  - Edge tracking by hysteresis  
- Real-time processing using OpenCV for Android  
- Interactive controls to adjust parameters:
  - Kernel size  
  - Threshold values  
  - Sigma values  
- Side-by-side comparison of original and processed images  
- Zoom and pan functionality for detailed inspection  

### Future Enhancements
- Image storage capabilities  
- History of processed images  
- Favorite image saving  
- Dark mode support  
- Performance metrics display  
- Custom kernel configuration  

---

##  Prerequisites
- Android **8.0 (Oreo)** or higher  
- OpenCV **4.5+** for Android  
- Minimum SDK: **26**  

---

## Installation

1. Clone the repository:
```bash
git clone https://github.com/TianK003/CannyEdgeDetector-Android.git
```

2. ### Add OpenCV for Android
  - Download [OpenCV for Android](https://opencv.org/releases/).
  -  Import the module in AndroidStudio: File → New → Import Module → Select OpenCV
  
3. Add OpenCV dependency in your **app/build.gradle.kts**:
```gradle
implementation(project(":opencv"))
```
4. Sync Gradle and build the project.
