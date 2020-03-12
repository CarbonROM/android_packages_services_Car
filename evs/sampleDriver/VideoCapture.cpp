/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <stdio.h>
#include <stdlib.h>
#include <error.h>
#include <errno.h>
#include <iomanip>
#include <memory.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/mman.h>

#include <android-base/logging.h>

#include "assert.h"

#include "VideoCapture.h"


// NOTE:  This developmental code does not properly clean up resources in case of failure
//        during the resource setup phase.  Of particular note is the potential to leak
//        the file descriptor.  This must be fixed before using this code for anything but
//        experimentation.
bool VideoCapture::open(const char* deviceName, const int32_t width, const int32_t height) {
    // If we want a polling interface for getting frames, we would use O_NONBLOCK
//    int mDeviceFd = open(deviceName, O_RDWR | O_NONBLOCK, 0);
    mDeviceFd = ::open(deviceName, O_RDWR, 0);
    if (mDeviceFd < 0) {
        PLOG(ERROR) << "failed to open device " << deviceName;
        return false;
    }

    v4l2_capability caps;
    {
        int result = ioctl(mDeviceFd, VIDIOC_QUERYCAP, &caps);
        if (result  < 0) {
            PLOG(ERROR) << "failed to get device caps for " << deviceName;
            return false;
        }
    }

    // Report device properties
    LOG(INFO) << "Open Device: " << deviceName << " (fd = " << mDeviceFd << ")";
    LOG(INFO) << "  Driver: " << caps.driver;
    LOG(INFO) << "  Card: " << caps.card;
    LOG(INFO) << "  Version: " << ((caps.version >> 16) & 0xFF)
                               << "." << ((caps.version >> 8) & 0xFF)
                               << "." << (caps.version & 0xFF);
    LOG(INFO) << "  All Caps: " << std::hex << std::setw(8) << caps.capabilities;
    LOG(INFO) << "  Dev Caps: " << std::hex << caps.device_caps;

    // Enumerate the available capture formats (if any)
    LOG(INFO) << "Supported capture formats:";
    v4l2_fmtdesc formatDescriptions;
    formatDescriptions.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    for (int i=0; true; i++) {
        formatDescriptions.index = i;
        if (ioctl(mDeviceFd, VIDIOC_ENUM_FMT, &formatDescriptions) == 0) {
            LOG(INFO) << "  " << std::setw(2) << i
                      << ": " << formatDescriptions.description
                      << " " << std::hex << std::setw(8) << formatDescriptions.pixelformat
                      << " " << std::hex << formatDescriptions.flags;
        } else {
            // No more formats available
            break;
        }
    }

    // Verify we can use this device for video capture
    if (!(caps.capabilities & V4L2_CAP_VIDEO_CAPTURE) ||
        !(caps.capabilities & V4L2_CAP_STREAMING)) {
        // Can't do streaming capture.
        LOG(ERROR) << "Streaming capture not supported by " << deviceName;
        return false;
    }

    // Set our desired output format
    v4l2_format format;
    format.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    format.fmt.pix.pixelformat = V4L2_PIX_FMT_UYVY;
    format.fmt.pix.width = width;
    format.fmt.pix.height = height;
    LOG(INFO) << "Requesting format: "
              << ((char*)&format.fmt.pix.pixelformat)[0]
              << ((char*)&format.fmt.pix.pixelformat)[1]
              << ((char*)&format.fmt.pix.pixelformat)[2]
              << ((char*)&format.fmt.pix.pixelformat)[3]
              << "(" << std::hex << std::setw(8) << format.fmt.pix.pixelformat << ")";

    if (ioctl(mDeviceFd, VIDIOC_S_FMT, &format) < 0) {
        PLOG(ERROR) << "VIDIOC_S_FMT failed";
    }

    // Report the current output format
    format.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (ioctl(mDeviceFd, VIDIOC_G_FMT, &format) == 0) {

        mFormat = format.fmt.pix.pixelformat;
        mWidth  = format.fmt.pix.width;
        mHeight = format.fmt.pix.height;
        mStride = format.fmt.pix.bytesperline;

        LOG(INFO) << "Current output format:  "
                  << "fmt=0x" << std::hex << format.fmt.pix.pixelformat
                  << ", " << std::dec << format.fmt.pix.width << " x " << format.fmt.pix.height
                  << ", pitch=" << format.fmt.pix.bytesperline;
    } else {
        PLOG(ERROR) << "VIDIOC_G_FMT failed";
        return false;
    }

    // Make sure we're initialized to the STOPPED state
    mRunMode = STOPPED;
    mFrameReady = false;

    // Ready to go!
    return true;
}


void VideoCapture::close() {
    LOG(DEBUG) << __FUNCTION__;
    // Stream should be stopped first!
    assert(mRunMode == STOPPED);

    if (isOpen()) {
        LOG(DEBUG) << "closing video device file handle " << mDeviceFd;
        ::close(mDeviceFd);
        mDeviceFd = -1;
    }
}


bool VideoCapture::startStream(std::function<void(VideoCapture*, imageBuffer*, void*)> callback) {
    // Set the state of our background thread
    int prevRunMode = mRunMode.fetch_or(RUN);
    if (prevRunMode & RUN) {
        // The background thread is already running, so we can't start a new stream
        LOG(ERROR) << "Already in RUN state, so we can't start a new streaming thread";
        return false;
    }

    // Tell the L4V2 driver to prepare our streaming buffers
    v4l2_requestbuffers bufrequest;
    bufrequest.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    bufrequest.memory = V4L2_MEMORY_MMAP;
    bufrequest.count = 1;
    if (ioctl(mDeviceFd, VIDIOC_REQBUFS, &bufrequest) < 0) {
        PLOG(ERROR) << "VIDIOC_REQBUFS failed";
        return false;
    }

    // Get the information on the buffer that was created for us
    memset(&mBufferInfo, 0, sizeof(mBufferInfo));
    mBufferInfo.type     = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    mBufferInfo.memory   = V4L2_MEMORY_MMAP;
    mBufferInfo.index    = 0;
    if (ioctl(mDeviceFd, VIDIOC_QUERYBUF, &mBufferInfo) < 0) {
        PLOG(ERROR) << "VIDIOC_QUERYBUF failed";
        return false;
    }

    LOG(INFO) << "Buffer description:";
    LOG(INFO) << "  offset: " << mBufferInfo.m.offset;
    LOG(INFO) << "  length: " << mBufferInfo.length;
    LOG(INFO) << "  flags : " << std::hex << mBufferInfo.flags;

    // Get a pointer to the buffer contents by mapping into our address space
    mPixelBuffer = mmap(
            NULL,
            mBufferInfo.length,
            PROT_READ | PROT_WRITE,
            MAP_SHARED,
            mDeviceFd,
            mBufferInfo.m.offset
    );
    if( mPixelBuffer == MAP_FAILED) {
        PLOG(ERROR) << "mmap() failed";
        return false;
    }
    memset(mPixelBuffer, 0, mBufferInfo.length);
    LOG(INFO) << "Buffer mapped at " << mPixelBuffer;

    // Queue the first capture buffer
    if (ioctl(mDeviceFd, VIDIOC_QBUF, &mBufferInfo) < 0) {
        PLOG(ERROR) << "VIDIOC_QBUF failed";
        return false;
    }

    // Start the video stream
    int type = mBufferInfo.type;
    if (ioctl(mDeviceFd, VIDIOC_STREAMON, &type) < 0) {
        PLOG(ERROR) << "VIDIOC_STREAMON failed";
        return false;
    }

    // Remember who to tell about new frames as they arrive
    mCallback = callback;

    // Fire up a thread to receive and dispatch the video frames
    mCaptureThread = std::thread([this](){ collectFrames(); });

    LOG(DEBUG) << "Stream started.";
    return true;
}


void VideoCapture::stopStream() {
    // Tell the background thread to stop
    int prevRunMode = mRunMode.fetch_or(STOPPING);
    if (prevRunMode == STOPPED) {
        // The background thread wasn't running, so set the flag back to STOPPED
        mRunMode = STOPPED;
    } else if (prevRunMode & STOPPING) {
        LOG(ERROR) << "stopStream called while stream is already stopping.  "
                   << "Reentrancy is not supported!";
        return;
    } else {
        // Block until the background thread is stopped
        if (mCaptureThread.joinable()) {
            mCaptureThread.join();
        }

        // Stop the underlying video stream (automatically empties the buffer queue)
        int type = mBufferInfo.type;
        if (ioctl(mDeviceFd, VIDIOC_STREAMOFF, &type) < 0) {
            PLOG(ERROR) << "VIDIOC_STREAMOFF failed";
        }

        LOG(DEBUG) << "Capture thread stopped.";
    }

    // Unmap the buffers we allocated
    munmap(mPixelBuffer, mBufferInfo.length);

    // Tell the L4V2 driver to release our streaming buffers
    v4l2_requestbuffers bufrequest;
    bufrequest.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    bufrequest.memory = V4L2_MEMORY_MMAP;
    bufrequest.count = 0;
    ioctl(mDeviceFd, VIDIOC_REQBUFS, &bufrequest);

    // Drop our reference to the frame delivery callback interface
    mCallback = nullptr;
}


void VideoCapture::markFrameReady() {
    mFrameReady = true;
};


bool VideoCapture::returnFrame() {
    // We're giving the frame back to the system, so clear the "ready" flag
    mFrameReady = false;

    // Requeue the buffer to capture the next available frame
    if (ioctl(mDeviceFd, VIDIOC_QBUF, &mBufferInfo) < 0) {
        PLOG(ERROR) << "VIDIOC_QBUF failed";
        return false;
    }

    return true;
}


// This runs on a background thread to receive and dispatch video frames
void VideoCapture::collectFrames() {
    // Run until our atomic signal is cleared
    while (mRunMode == RUN) {
        // Wait for a buffer to be ready
        if (ioctl(mDeviceFd, VIDIOC_DQBUF, &mBufferInfo) < 0) {
            PLOG(ERROR) << "VIDIOC_DQBUF failed";
            break;
        }

        markFrameReady();

        // If a callback was requested per frame, do that now
        if (mCallback) {
            mCallback(this, &mBufferInfo, mPixelBuffer);
        }
    }

    // Mark ourselves stopped
    LOG(DEBUG) << "VideoCapture thread ending";
    mRunMode = STOPPED;
}


int VideoCapture::setParameter(v4l2_control& control) {
    int status = ioctl(mDeviceFd, VIDIOC_S_CTRL, &control);
    if (status < 0) {
        PLOG(ERROR) << "Failed to program a parameter value "
                    << "id = " << std::hex << control.id;
    }

    return status;
}


int VideoCapture::getParameter(v4l2_control& control) {
    int status = ioctl(mDeviceFd, VIDIOC_G_CTRL, &control);
    if (status < 0) {
        PLOG(ERROR) << "Failed to read a parameter value"
                    << " fd = " << std::hex << mDeviceFd
                    << " id = " << control.id;
    }

    return status;
}
