#pragma once

#include <atomic>

namespace rawrcam::camera {

// X14U Settings → Focus and exposure (Nov 17): whether continuous AF follows the main face, and whether exposure is
// set for it (Camera2 AE regions; rawr AE's matrix meter weights the face's AF area). Both on by default; a tapped
// focus or exposure point still takes priority.
struct FaceOptions {
    std::atomic<bool> focus{true};
    std::atomic<bool> exposure{true};
};

inline FaceOptions& faceOptions() {
    static FaceOptions options;
    return options;
}

}  // namespace rawrcam::camera
