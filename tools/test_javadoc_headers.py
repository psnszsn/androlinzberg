import importlib.util
import io
from pathlib import Path
import struct
import shutil
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("audit", Path(__file__).with_name("audit-javadoc-headers.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def constants(*values):
    return (b"\xca\xfe\xba\xbe\x00\x00\x00\x41" + struct.pack(">H", len(values) + 1)
            + b"".join(b"\x01" + struct.pack(">H", len(value)) + value for value in values))



def executable_class(code=b"\x10\x2a\xac", *, parent=6, stack_map=False, member_call=False):
    # Minimal class with an unused header owner, like javac's inlined constant.
    utf = lambda value: b"\x01" + struct.pack(">H", len(value)) + value
    pool = [utf(b"android/media/Image"), b"\x07\x00\x01", utf(b"Fixture"),
            b"\x07\x00\x03", utf(b"java/lang/Object"), b"\x07\x00\x05",
            utf(b"value"), utf(b"()I"), utf(b"Code"), utf(b"StackMapTable")]
    if member_call:
        pool += [b"\x0c\x00\x07\x00\x08", b"\x0a\x00\x02\x00\x0b"]
        code = b"\xb8\x00\x0c\xac"  # invokestatic Image.value; ireturn
    nested = b"\x00\x00"
    if stack_map:
        # full_frame with an Object verification type referencing the header.
        frames = b"\x00\x01\xff\x00\x00\x00\x00\x00\x01\x07\x00\x02"
        nested = b"\x00\x01" + struct.pack(">HI", 10, len(frames)) + frames
    body = struct.pack(">HHI", 1, 0, len(code)) + code + b"\x00\x00" + nested
    method = struct.pack(">HHHHHI", 9, 7, 8, 1, 9, len(body)) + body
    return (b"\xca\xfe\xba\xbe\x00\x00\x00\x41" + struct.pack(">H", len(pool) + 1)
            + b"".join(pool) + struct.pack(">HHHHHH", 0x21, 4, parent, 0, 0, 1)
            + method + b"\x00\x00")


class HeaderAuditTest(unittest.TestCase):
    def check_jar(self, entry, data):
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as jar:
            jar.writestr(entry, data)
        return audit.audit(archive, ["android/media/Image"])

    def test_unused_import_has_no_runtime_reference(self):
        self.assertEqual([], self.check_jar("Fence.class", constants(b"android/hardware/SyncFence")))

    def test_packaging_header_is_rejected(self):
        self.assertTrue(self.check_jar("android/media/Image.class", constants()))

    def test_types_arrays_methods_generics_and_annotations_are_rejected(self):
        for value in (b"android/media/Image", b"[Landroid/media/Image;",
                      b"(I)Landroid/media/Image;", b"Ljava/util/List<Landroid/media/Image;>;",
                      b"Landroid/media/Image<TT;>;", b"Landroid/media/Image;"):
            with self.subTest(value=value):
                self.assertTrue(self.check_jar("Fence.class", constants(value)))

    def test_similar_name_is_not_a_dependency(self):
        self.assertEqual([], self.check_jar("Fence.class", constants(b"Landroid/media/ImageReader;")))

    def test_double_slot_and_modern_tags(self):
        pool = (b"\x05" + b"\0" * 8 + b"\x06" + b"\0" * 8 + b"\x11" + b"\0" * 4
                + b"\x12" + b"\0" * 4 + b"\x13\0\0" + b"\x14\0\0")
        data = b"\xca\xfe\xba\xbe\0\0\0\x41\0\x09" + pool
        self.assertEqual([], list(audit.utf8_constants(data)))

    @unittest.skipUnless(shutil.which("javap"), "JDK classfile reader required")
    def test_inlined_constant_owner_is_not_a_runtime_dependency(self):
        self.assertEqual([], self.check_jar("Fixture.class", executable_class()))

    @unittest.skipUnless(shutil.which("javap"), "JDK classfile reader required")
    def test_executable_and_metadata_class_references_are_rejected(self):
        for data in (executable_class(b"\x12\x02\xb0"),  # ldc Image.class
                     executable_class(parent=2),
                     executable_class(member_call=True),
                     executable_class(stack_map=True)):
            with self.subTest(data=data):
                self.assertTrue(self.check_jar("Fixture.class", data))

    def test_malformed_input_fails_closed(self):
        for data in (b"", constants(b"hello")[:-1], constants()[:-1],
                     b"\xca\xfe\xba\xbe\0\0\0\x41\0\x02\xff"):
            with self.subTest(data=data):
                self.assertTrue(self.check_jar("Fence.class", data))


if __name__ == "__main__":
    unittest.main()
