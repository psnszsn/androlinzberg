#pragma once
// Real GPU sync_file fixture. A bounded host-controlled VkEvent delays queue
// completion; teardown always releases it before destroying Vulkan objects.
#include <vulkan/vulkan.h>
#include <ui/Fence.h>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <iostream>
#include <mutex>
#include <thread>
#include <vector>

class DelayedGpuFence {
  VkInstance instance = VK_NULL_HANDLE;
  VkDevice device = VK_NULL_HANDLE;
  VkCommandPool pool = VK_NULL_HANDLE;
  VkEvent event = VK_NULL_HANDLE;
  VkFence completion = VK_NULL_HANDLE;
  android::sp<android::Fence> exported;
  std::mutex mutex;
  std::condition_variable changed;
  bool released = false;
  bool expired = false;
  std::thread releaser;
public:
  DelayedGpuFence() {
    std::cerr << "GPU fence requires Vulkan 1.1 hardware and exportable SYNC_FD fences" << std::endl;
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo create{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    create.pApplicationInfo = &app;
    assert(vkCreateInstance(&create, nullptr, &instance) == VK_SUCCESS);
    uint32_t count = 0;
    assert(vkEnumeratePhysicalDevices(instance, &count, nullptr) == VK_SUCCESS);
    std::vector<VkPhysicalDevice> candidates(count);
    assert(vkEnumeratePhysicalDevices(instance, &count, candidates.data()) == VK_SUCCESS);
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    for (auto candidate : candidates) {
      VkPhysicalDeviceProperties properties;
      vkGetPhysicalDeviceProperties(candidate, &properties);
      if (properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU) continue;
      VkPhysicalDeviceExternalFenceInfo query{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_FENCE_INFO};
      query.handleType = VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT;
      VkExternalFenceProperties support{VK_STRUCTURE_TYPE_EXTERNAL_FENCE_PROPERTIES};
      vkGetPhysicalDeviceExternalFenceProperties(candidate, &query, &support);
      if (!(support.externalFenceFeatures & VK_EXTERNAL_FENCE_FEATURE_EXPORTABLE_BIT)) continue;
      physical = candidate;
      std::cerr << "GPU fence device: " << properties.deviceName << std::endl;
      break;
    }
    // An absent hardware path is a failed capability gate, never a skipped pass.
    assert(physical && "GPU with exportable sync_file fences required");
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, nullptr);
    std::vector<VkQueueFamilyProperties> families(count);
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, families.data());
    // Keep the deliberate device-side wait off the graphics engine. On Mesa,
    // a graphics VkEvent wait can stall unrelated EGL contexts on that engine.
    // Timeline waits cannot substitute here: exporting their not-yet-submitted
    // sync_file may block until the host signals the semaphore.
    uint32_t family = 0;
    while (family < count && (!(families[family].queueFlags & VK_QUEUE_COMPUTE_BIT) ||
                              (families[family].queueFlags & VK_QUEUE_GRAPHICS_BIT))) ++family;
    if (family == count) {
      family = 0;
      while (family < count && !(families[family].queueFlags & VK_QUEUE_GRAPHICS_BIT)) ++family;
    }
    assert(family < count);
    float priority = 1;
    VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queueInfo.queueFamilyIndex = family;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;
    const char *extension = VK_KHR_EXTERNAL_FENCE_FD_EXTENSION_NAME;
    VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    deviceInfo.enabledExtensionCount = 1;
    deviceInfo.ppEnabledExtensionNames = &extension;
    assert(vkCreateDevice(physical, &deviceInfo, nullptr, &device) == VK_SUCCESS);
    VkQueue queue;
    vkGetDeviceQueue(device, family, 0, &queue);
    VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.queueFamilyIndex = family;
    assert(vkCreateCommandPool(device, &poolInfo, nullptr, &pool) == VK_SUCCESS);
    VkCommandBufferAllocateInfo allocate{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocate.commandPool = pool;
    allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocate.commandBufferCount = 1;
    VkCommandBuffer command;
    assert(vkAllocateCommandBuffers(device, &allocate, &command) == VK_SUCCESS);
    VkEventCreateInfo eventInfo{VK_STRUCTURE_TYPE_EVENT_CREATE_INFO};
    assert(vkCreateEvent(device, &eventInfo, nullptr, &event) == VK_SUCCESS);
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    assert(vkBeginCommandBuffer(command, &begin) == VK_SUCCESS);
    vkCmdWaitEvents(command, 1, &event, VK_PIPELINE_STAGE_HOST_BIT,
                   VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, nullptr, 0, nullptr, 0, nullptr);
    assert(vkEndCommandBuffer(command) == VK_SUCCESS);
    VkExportFenceCreateInfo exportInfo{VK_STRUCTURE_TYPE_EXPORT_FENCE_CREATE_INFO};
    exportInfo.handleTypes = VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT;
    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    fenceInfo.pNext = &exportInfo;
    assert(vkCreateFence(device, &fenceInfo, nullptr, &completion) == VK_SUCCESS);
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &command;
    assert(vkQueueSubmit(queue, 1, &submit, completion) == VK_SUCCESS);
    releaser = std::thread([this] {
      std::unique_lock lock(mutex);
      expired = !changed.wait_for(lock, std::chrono::seconds(3), [this] { return released; });
      assert(vkSetEvent(device, event) == VK_SUCCESS);
    });
    auto getFd = reinterpret_cast<PFN_vkGetFenceFdKHR>(vkGetDeviceProcAddr(device, "vkGetFenceFdKHR"));
    VkFenceGetFdInfoKHR get{VK_STRUCTURE_TYPE_FENCE_GET_FD_INFO_KHR};
    get.fence = completion;
    get.handleType = VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT;
    int fd = -1;
    const auto status = getFd ? getFd(device, &get, &fd) : VK_ERROR_EXTENSION_NOT_PRESENT;
    if (status != VK_SUCCESS || fd < 0) release();
    assert(status == VK_SUCCESS && fd >= 0);
    exported = android::sp<android::Fence>::make(fd);
  }
  const android::sp<android::Fence> &fence() const { return exported; }
  void release() {
    { std::lock_guard lock(mutex); released = true; }
    changed.notify_one();
    if (releaser.joinable()) releaser.join();
  }
  bool timedOut() { std::lock_guard lock(mutex); return expired; }
  ~DelayedGpuFence() {
    release();
    assert(vkDeviceWaitIdle(device) == VK_SUCCESS);
    vkDestroyFence(device, completion, nullptr);
    vkDestroyEvent(device, event, nullptr);
    vkDestroyCommandPool(device, pool, nullptr);
    vkDestroyDevice(device, nullptr);
    vkDestroyInstance(instance, nullptr);
  }
};
