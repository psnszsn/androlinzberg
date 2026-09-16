# Host scheduling

Both ordinary user launches and configured system installations are supported.
No automatic privilege escalation is performed.

The original Process JNI and libutils request real Linux thread priorities. If
the kernel permits the change, it is applied normally. If a boost is denied for
a live thread in the calling process, the native host boundary leaves that
thread's kernel priority unchanged and lets the application continue. It logs
this fallback once per process. `Process.getThreadPriority` still reports the
actual kernel value. Missing-thread errors, cross-process permission failures,
and non-boost failures retain their original errors.

This best-effort fallback is an explicit native adaptation below the original
JNI. It avoids framework startup crashes on an ordinary desktop login. It does
not supply realtime scheduling or Android cgroup task profiles.

For a system installation, the optional configuration below supplies the same
`RLIMIT_NICE=40:40` used by pinned Android's `system/core/rootdir/init.rc`. That
permits normal scheduling priorities from 19 to −20. Applications still run as
ordinary users; the limit is inherited by launcher children. The same build
automatically uses whichever permissions the host supplies.

For the current terminal, an administrator can set the limit on that shell:

```sh
sudo prlimit --pid "$$" --nice=40:40
```

Launch the development shell, build, tests, or application from that terminal.
The permission is inherited by its children. This command does not change
persistent host configuration. Check the actual limit with:

```sh
prlimit --pid "$$" --nice
```

On NixOS, import `artzig/nixos/host-scheduling.nix` (also exported as the Artzig
flake's `nixosModules.hostScheduling`) and select accounts with explicit UIDs:

```nix
programs.androlinzberg.schedulingUsers = [ "alice" ];
users.users.alice.uid = 1000;
```

The module configures PAM and the selected accounts' systemd user-manager
instances. After applying the NixOS configuration, fully log out and back in;
existing shells and user managers retain their old limits. Verify the limit in
the process that launches androlinzberg. Selecting an account permits its
processes to request these nice priorities, not only Android applications.
