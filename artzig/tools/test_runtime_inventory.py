#!/usr/bin/env python3
"""Loader-output regressions for the runtime exception inventory."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('inventory', Path(__file__).with_name('runtime-inventory.py'))
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


class LoaderResolutionTest(unittest.TestCase):
    def test_missing_dependency_is_an_error(self):
        with self.assertRaisesRegex(ValueError, 'unresolved'):
            inventory.resolved_libraries('libmissing.so => not found\n')

    def test_resolves_soname_absolute_interpreter_and_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            library = root / 'library'
            library.write_bytes(b'ELF')
            alias = root / 'alias'
            alias.symlink_to(library)
            result = inventory.resolved_libraries(
                f'libone.so => {alias} (0x123)\n'
                f'/prefix/ld-linux.so => {library} (0xabc)\n'
                f'{library} (0x456)\nlinux-vdso.so.1 (0x789)\n')
            self.assertEqual(result, {'libone.so': library, 'ld-linux.so': library, 'library': library})

    def test_nonexistent_resolved_path_is_an_error(self):
        with self.assertRaises(FileNotFoundError):
            inventory.resolved_libraries('libbad.so => /nonexistent/azk-library.so (0x123)\n')


if __name__ == '__main__':
    unittest.main()
