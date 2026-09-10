// Pinned art::Runtime::BlockSignals (art/runtime/runtime.cc): ART's signal
// catcher consumes QUIT/USR1, and PIPE is blocked. Hosted Binder/session workers
// start before JNI_CreateJavaVM, so their parent must establish this mask first.
#include <pthread.h>
#include <signal.h>

int azk_art_prepare_signals(void) {
    sigset_t signals;
    sigemptyset(&signals);
    sigaddset(&signals, SIGPIPE);
    sigaddset(&signals, SIGQUIT);
    sigaddset(&signals, SIGUSR1);
    return pthread_sigmask(SIG_BLOCK, &signals, 0);
}
