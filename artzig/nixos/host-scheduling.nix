# Supply Android init.rc's RLIMIT_NICE without running the launcher as root.
{ config, lib, ... }:
let
  users = config.programs.androlinzberg.schedulingUsers;
in {
  options.programs.androlinzberg.schedulingUsers = lib.mkOption {
    type = lib.types.listOf lib.types.str;
    default = [];
    description = "Users allowed to select Android's full normal thread-priority range.";
  };

  config = lib.mkIf (users != []) {
    assertions = map (name: {
      assertion = builtins.hasAttr name config.users.users
        && config.users.users.${name}.uid != null;
      message = "androlinzberg scheduling user ${name} needs an explicit users.users.${name}.uid.";
    }) users;

    security.pam.loginLimits = map (name: {
      domain = name;
      type = "-";
      item = "nice";
      value = "-20";
    }) users;

    # Desktop applications started by the user manager do not inherit a new
    # terminal login's PAM limits. Configure only the selected manager instances.
    systemd.services = builtins.listToAttrs (map (name: {
      name = "user@${toString config.users.users.${name}.uid}";
      value = {
        overrideStrategy = "asDropin";
        serviceConfig.LimitNICE = "40:40";
      };
    }) users);
  };
}
