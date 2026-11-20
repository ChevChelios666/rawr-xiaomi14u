#pragma once
#include <sys/system_properties.h>
#include <tonemap/TonemapEngine.h>

#include <array>
#include <memory>
#include <string>

#include "diagnostics/timing/RecentPeak.h"
#include "develop/render/DcpRenderStage.h"
#include "imaging/FrameLimits.h"
#include "post_demosaic/PostDemosaicProcessor.h"
#include "video/VideoFastPath.h"
#include "video_pipeline/VideoDemosaic.h"
#include "video_pipeline/VideoProcessingConfig.h"
#include "vulkan/ImageResources.h"
namespace rawrcam::vulkan {
class VulkanContext;
}
namespace rawrcam::video {
// The on-screen monitor while recording. X14U (Nov 20) test switch: `adb shell setprop debug.rawr.half_monitor 1`
// (then reopen the app) writes it at half resolution (one pixel per 2x2) for recordings wider than 2560 px (4K, Open
// Gate), which saves GPU time in the 33 ms budget (utalpt/rawr f64ee9f); 1080p keeps a full-size one. Unset: full size
// as before (reverted in 8ba5409 while hunting the 4K drops; back as a switch). Read once per app run, so the monitor
// images and the frames recorded into them always agree. The tonemap shader follows the monitor's size.
inline bool videoHalfMonitorEnabled() noexcept {
    static const bool enabled = [] {
        char value[PROP_VALUE_MAX] = {};
        return __system_property_get("debug.rawr.half_monitor", value) > 0 && value[0] == '1';
    }();
    return enabled;
}
inline VkExtent2D videoMonitorExtent(uint32_t width, uint32_t height) noexcept {
    if (width <= 2560u || !videoHalfMonitorEnabled()) return {width, height};
    return {(width + 1u) / 2u, (height + 1u) / 2u};
}
class VideoProcessingResources final {
   public:
    explicit VideoProcessingResources(vulkan::VulkanContext& context) : context_(context) {}
    ~VideoProcessingResources();
    struct ProcessingKey {
        uint32_t width = 0, height = 0, rawWidth = 0, rawHeight = 0;
        VkFormat outputFormat = VK_FORMAT_UNDEFINED;
        uint32_t fccSteps = 0;
        float defringeStrength = 0.0f;
        // X14U video crop: the sensor window (0x0 = the original rule); part of the key so a new crop rebuilds.
        uint32_t sourceWidth = 0, sourceHeight = 0;
        bool operator==(const ProcessingKey& o) const noexcept {
            return width == o.width && height == o.height && rawWidth == o.rawWidth && rawHeight == o.rawHeight &&
                   outputFormat == o.outputFormat && fccSteps == o.fccSteps && defringeStrength == o.defringeStrength &&
                   sourceWidth == o.sourceWidth && sourceHeight == o.sourceHeight;
        }
        bool operator!=(const ProcessingKey& o) const noexcept { return !(*this == o); }
        std::string describe() const {
            return std::to_string(width) + "x" + std::to_string(height) + " raw " + std::to_string(rawWidth) + "x" +
                   std::to_string(rawHeight) + " fmt " + std::to_string(outputFormat) + " fcc " +
                   std::to_string(fccSteps) + " defringe " + std::to_string(defringeStrength) + " source " +
                   std::to_string(sourceWidth) + "x" + std::to_string(sourceHeight);
        }
    };
    // Processing built ahead of a recording, possibly off the engine lock.
    struct Prepared {
        ProcessingKey key{};
        VkDevice device = VK_NULL_HANDLE;
        std::unique_ptr<tonemap::TonemapEngine> tonemap;
        std::unique_ptr<VideoDemosaic> demosaic;
        // utalpt's one-pass fast path (RAW -> encoder YUV) and the RGBA16F tonemap that bakes its LUT.
        std::unique_ptr<tonemap::TonemapEngine> fastBake;
        std::unique_ptr<VideoFastPath> fast;
        std::array<std::unique_ptr<rawr::post::PostDemosaicProcessor>, rawrcam::imaging::kRealtimeFramesInFlight>
            post{};
        std::array<rawrcam::vulkan::OwnedImage, rawrcam::imaging::kRealtimeFramesInFlight> monitor{};
        rawrcam::vulkan::OwnedImage dummyMonitor{};
        std::array<VkQueryPool, rawrcam::imaging::kRealtimeFramesInFlight> stagePools{};
        std::array<VkQueryPool, rawrcam::imaging::kRealtimeFramesInFlight> monitorPools{};
        Prepared() = default;
        Prepared(Prepared&&) = default;
        Prepared& operator=(Prepared&&) = delete;
        ~Prepared();
    };
    static Prepared prepare(vulkan::VulkanContext& context, const ProcessingKey& key,
                            const VideoProcessingConfig& config, const tonemap::lut::LutChain* renderLut);
    void install(Prepared&& prepared);
    void release() noexcept;
    void updateRenderLut(const tonemap::lut::LutChain* renderLut, bool recording);
    bool hasProcessing(const ProcessingKey& key) const noexcept { return tonemap_ && processingKey_ == key; }
    void beginRecording();
    const char* rawStageMethod() const noexcept { return demosaic_ ? demosaic_->method() : "none"; }
    VkImageView monitorView(uint32_t slot) const noexcept { return monitor_[slot].view; }
    VkImage monitorImage(uint32_t slot) const noexcept { return monitor_[slot].image; }
    void beginMonitorTiming(VkCommandBuffer command, uint32_t slot);
    void endMonitorTiming(VkCommandBuffer command, uint32_t slot);
    std::string stageTimingJson() const;

   private:
    friend class VideoRecorder;
    vulkan::VulkanContext& context_;
    ProcessingKey processingKey_{};
    std::unique_ptr<tonemap::TonemapEngine> tonemap_;
    std::unique_ptr<VideoDemosaic> demosaic_;
    std::unique_ptr<tonemap::TonemapEngine> fastBake_;
    std::unique_ptr<VideoFastPath> fast_;
    bool fastLogged_ = false;
    std::array<std::unique_ptr<rawr::post::PostDemosaicProcessor>, rawrcam::imaging::kRealtimeFramesInFlight> post_{};
    std::array<std::unique_ptr<develop::rendered::DcpRenderStage>, rawrcam::imaging::kRealtimeFramesInFlight> dcp_{};
    std::array<rawrcam::vulkan::OwnedImage, rawrcam::imaging::kRealtimeFramesInFlight> monitor_{};
    std::array<bool, rawrcam::imaging::kRealtimeFramesInFlight> monitorInitialized_{};
    // Per-slot timestamps: 0-5 and 10-11 are written by PostDemosaicProcessor,
    // 12-15 bracket demosaic, post and tonemap. Read back when a slot is reused.
    // PostDemosaicProcessor also writes 16-17 (defringe + tone tap).
    static constexpr uint32_t kStageQueries = 18;
    static constexpr uint32_t kStageCount = 8;
    void collectStageTiming(uint32_t frameSlot);
    std::array<VkQueryPool, rawrcam::imaging::kRealtimeFramesInFlight> stagePools_{};
    std::array<VkQueryPool, rawrcam::imaging::kRealtimeFramesInFlight> monitorPools_{};
    std::array<bool, rawrcam::imaging::kRealtimeFramesInFlight> monitorPending_{};
    double monitorSumMs_ = 0.0;
    uint64_t monitorSamples_ = 0;
    std::array<bool, rawrcam::imaging::kRealtimeFramesInFlight> stagePending_{};
    std::array<double, kStageCount> stageSumMs_{};
    uint64_t stageSamples_ = 0;
    rawrcam::diagnostics::RecentPeak totalPeak_;
    double timestampPeriodNs_ = 1.0;
    rawrcam::vulkan::OwnedImage dummyMonitor_{};
    bool dummyMonitorInitialized_ = false;
};
}  // namespace rawrcam::video
