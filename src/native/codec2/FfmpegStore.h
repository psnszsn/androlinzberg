#pragma once
#include <C2Component.h>
#include <memory>
namespace azk::codec2 {
std::shared_ptr<C2ComponentStore> createStore(C2Component::domain_t domain);
}
