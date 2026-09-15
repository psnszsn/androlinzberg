#!/usr/bin/env python3
"""Run Skia's original Soong config writer with the selected GN features."""
import importlib.util
from pathlib import Path
import sys

generator, output, *defines = sys.argv[1:]
spec = importlib.util.spec_from_file_location("gn_to_bp_utils", generator)
module = importlib.util.module_from_spec(spec)
sys.argv = [generator]
spec.loader.exec_module(module)
header = Path(output) / "include/config/SkUserConfig.h"
header.parent.mkdir(parents=True, exist_ok=True)
module.WriteUserConfig(header, defines)
