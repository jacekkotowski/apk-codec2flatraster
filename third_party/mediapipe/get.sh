#!/bin/bash
# Fetches the MediaPipe Tasks libraries (Apache 2.0) and the two models the Puppet video format uses. Run by build.sh when out/ here is missing.
# Pinned versions, SHA-256 checked. Nothing here is committed (see /.gitignore): about 80 MB.
#   tasks-vision, tasks-core 1.2.3 from Google's Maven (dl.google.com/android/maven2): Java classes and the native library for arm64-v8a
#   guava, failureaccess, protobuf-javalite, flogger, flogger-system-backend from Maven Central: the Java libraries the classes call
#   face_landmarker.task, pose_landmarker_lite.task from storage.googleapis.com/mediapipe-models
# MediaPipe's remote usage statistics (it sends them to Google through the Android data-transport library) are NOT included: its
# TasksStatsLoggerFactory class is removed from the jar and src/com/google/mediapipe/tasks/core/logging/TasksStatsLoggerFactory.java stands in.
set -e
cd "$(dirname "$0")"
mkdir -p dl jars models x
G=https://dl.google.com/android/maven2/com/google/mediapipe; M=https://repo1.maven.org/maven2; S=https://storage.googleapis.com/mediapipe-models
get() { [ -s "$2" ] || curl -sS --retry 3 -o "$2" "$1"; }
get $G/tasks-vision/1.2.3/tasks-vision-1.2.3.aar dl/tasks-vision.aar
get $G/tasks-core/1.2.3/tasks-core-1.2.3.aar dl/tasks-core.aar
get $M/com/google/guava/guava/27.0.1-android/guava-27.0.1-android.jar jars/guava-27.0.1-android.jar
get $M/com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar jars/failureaccess-1.0.1.jar
get $M/com/google/protobuf/protobuf-javalite/4.26.1/protobuf-javalite-4.26.1.jar jars/protobuf-javalite-4.26.1.jar
get $M/com/google/flogger/flogger/0.6/flogger-0.6.jar jars/flogger-0.6.jar
get $M/com/google/flogger/flogger-system-backend/0.6/flogger-system-backend-0.6.jar jars/flogger-system-backend-0.6.jar
get $S/face_landmarker/face_landmarker/float16/1/face_landmarker.task models/face_landmarker.task
get $S/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task models/pose_landmarker_lite.task
sha256sum -c sums.txt
rm -rf x/*; unzip -q -o dl/tasks-core.aar -d x/core; unzip -q -o dl/tasks-vision.aar -d x/vision
cp x/core/classes.jar jars/tasks-core.jar; cp x/vision/classes.jar jars/tasks-vision.jar
zip -q -d jars/tasks-core.jar com/google/mediapipe/tasks/core/logging/TasksStatsLoggerFactory.class
mkdir -p lib/arm64-v8a; cp x/core/jni/arm64-v8a/libmediapipe_tasks_jni.so lib/arm64-v8a/
echo "MediaPipe ready"
