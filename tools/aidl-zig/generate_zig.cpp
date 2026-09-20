// Zig backend of the repository's AIDL compiler build (build/aidl.zig).
//
// The backend is deliberately declarative. For every AIDL type it emits the
// Zig data type plus the name of a codec from src/binder/aidl.zig; parcelable
// framing, nullability, status headers, proxy calls and server dispatch are
// implemented once in that runtime through comptime reflection over the
// emitted tables. Wire behaviour therefore follows the pinned NDK backend
// (generate_ndk.cpp), including the vector-size hint a proxy writes for an
// `out` array.
//
// Not expressible yet, and reported as errors: generic parcelables,
// ParcelableHolder, and @nullable fixed-size arrays.

#include "generate_zig.h"

#include <map>
#include <set>
#include <sstream>
#include <string>
#include <vector>

#include <android-base/strings.h>

#include "aidl_to_common.h"
#include "code_writer.h"
#include "logging.h"
#include "os.h"

using android::base::Join;
using android::base::Split;
using std::string;

namespace android {
namespace aidl {
namespace zig {
namespace {

// IBinder.FIRST_CALL_TRANSACTION.
constexpr int kFirstCallTransaction = 1;

constexpr const char* kKeywords[] = {
    "addrspace", "align",     "allowzero",   "and",      "anyframe",    "anytype",  "asm",
    "break",     "callconv",  "catch",       "comptime", "const",       "continue", "defer",
    "else",      "enum",      "errdefer",    "error",    "export",      "extern",   "fn",
    "for",       "if",        "inline",      "noalias",  "noinline",    "nosuspend", "opaque",
    "or",        "orelse",    "packed",      "pub",      "resume",      "return",   "linksection",
    "struct",    "suspend",   "switch",      "test",     "threadlocal", "try",      "union",
    "unreachable", "var",     "volatile",    "while",    "null",        "undefined", "true",
    "false",     "type",      "void",        "bool",     "anyerror",    "noreturn", "anyopaque",
    "usize",     "isize",     "self",        "allocator",
};

// A Zig identifier for an AIDL name. Primitive-looking names (i32, u8, f64)
// shadow builtin types, so they are quoted along with the keywords.
string Identifier(const string& name) {
  bool quote = false;
  for (const char* keyword : kKeywords) quote |= name == keyword;
  if (name.size() > 1 && (name[0] == 'i' || name[0] == 'u' || name[0] == 'f') &&
      name.find_first_not_of("0123456789", 1) == string::npos) {
    quote = true;
  }
  return quote ? "@\"" + name + "\"" : name;
}

struct TypeInfo {
  string zig;    // the Zig type generated code declares
  string codec;  // the aidl.codec expression that marshals it
  bool allocates = false;
};

// State of one output file: the root type it declares and what it imports.
class Unit {
 public:
  explicit Unit(const AidlDefinedType& root) : root_(root) {}

  // A Zig expression naming a defined type from inside this file.
  string Reference(const AidlDefinedType& type) {
    if (type.AsUnstructuredParcelable() != nullptr) {
      custom_ = true;
      return "aidl_custom.@\"" + type.GetCanonicalName() + "\"";
    }
    std::vector<string> path;
    const AidlDefinedType* top = &type;
    for (; top->GetParentType() != nullptr; top = top->GetParentType()) {
      path.insert(path.begin(), Identifier(top->GetName()));
    }
    path.insert(path.begin(), Identifier(top->GetName()));
    if (top == &root_) return Join(path, ".");

    string alias = "_" + top->GetCanonicalName();
    for (char& c : alias) {
      if (c == '.') c = '_';
    }
    string file;
    if (!root_.GetPackage().empty()) {
      for (size_t i = 0; i < Split(root_.GetPackage(), ".").size(); i++) file += "../";
    }
    for (const string& part : Split(top->GetCanonicalName(), ".")) file += part + "/";
    file.pop_back();
    imports_[alias] = "@import(\"" + file + ".zig\")." + path[0];
    path[0] = alias;
    return Join(path, ".");
  }

  const std::map<string, string>& Imports() const { return imports_; }
  bool UsesCustom() const { return custom_; }

 private:
  const AidlDefinedType& root_;
  std::map<string, string> imports_;
  bool custom_ = false;
};

class Generator {
 public:
  Generator(Unit& unit, CodeWriter& out) : unit_(unit), out_(out) {}

  bool Ok() const { return ok_; }

  void Type(const AidlDefinedType& type) {
    if (const auto* generic = type.AsParcelable(); generic != nullptr && generic->IsGeneric()) {
      Fail(type, "generic parcelables");
    } else if (const auto* iface = type.AsInterface(); iface != nullptr) {
      Interface(*iface);
    } else if (const auto* parcelable = type.AsStructuredParcelable(); parcelable != nullptr) {
      Parcelable(*parcelable);
    } else if (const auto* alternatives = type.AsUnionDeclaration(); alternatives != nullptr) {
      Union(*alternatives);
    } else if (const auto* enumeration = type.AsEnumDeclaration(); enumeration != nullptr) {
      Enum(*enumeration);
    }
    // An unstructured parcelable has no generated form: aidl_custom supplies it.
  }

 private:
  template <typename Node>
  void Fail(const Node& node, const string& what) {
    AIDL_ERROR(node) << "The Zig backend does not support " << what << ".";
    ok_ = false;
  }

  TypeInfo Named(const AidlTypeSpecifier& type) {
    static const std::map<string, TypeInfo> primitives = {
        {"boolean", {"bool", "c.Bool"}}, {"byte", {"i8", "c.Byte"}},
        {"char", {"u16", "c.Char"}},     {"int", {"i32", "c.Int"}},
        {"long", {"i64", "c.Long"}},     {"float", {"f32", "c.Float"}},
        {"double", {"f64", "c.Double"}}, {"String", {"[]const u8", "c.String", true}},
        {"IBinder", {"aidl.Binder", "c.Binder"}},
        {"ParcelFileDescriptor", {"aidl.File", "c.File"}},
    };
    const string& name = type.GetName();
    if (auto found = primitives.find(name); found != primitives.end()) return found->second;
    if (name == "List" && type.IsGeneric() && type.GetTypeParameters().size() == 1) {
      return Sequence(Element(*type.GetTypeParameters()[0], type.IsNullable()));
    }
    const AidlDefinedType* defined = type.GetDefinedType();
    if (defined == nullptr || type.IsGeneric()) {
      Fail(type, "the type " + type.Signature());
      return {"void", "void"};
    }
    const string reference = unit_.Reference(*defined);
    if (defined->AsInterface() != nullptr) {
      return {reference + ".Client", "c.Interface(" + reference + ")"};
    }
    if (defined->AsEnumDeclaration() != nullptr) return {reference, "c.Enum(" + reference + ")"};
    return {reference, "c.Parcelable(" + reference + ")", true};
  }

  static TypeInfo Optional(const TypeInfo& info) {
    return {"?" + info.zig, "c.Nullable(" + info.codec + ")", info.allocates};
  }

  static TypeInfo Sequence(const TypeInfo& element) {
    return {"[]const " + element.zig, "c.Array(" + element.codec + ")", true};
  }

  // An element of `T[]`/`List<T>`. A @nullable sequence of strings, binders or
  // parcelables also has nullable elements, as in the other backends.
  TypeInfo Element(const AidlTypeSpecifier& type, bool nullable_sequence) {
    TypeInfo element = Named(type);
    const bool reference_type = type.GetName() == "String" || type.GetName() == "IBinder" ||
                                type.GetName() == "ParcelFileDescriptor" ||
                                (type.GetDefinedType() != nullptr &&
                                 type.GetDefinedType()->AsEnumDeclaration() == nullptr);
    return nullable_sequence && reference_type ? Optional(element) : element;
  }

  // `field` types need a default value, so a Binder, interface or descriptor
  // held in a parcelable is optional even when AIDL does not say @nullable.
  TypeInfo TypeOf(const AidlTypeSpecifier& type, bool field = false) {
    TypeInfo info;
    if (type.IsFixedSizeArray()) {
      if (type.IsNullable()) Fail(type, "@nullable fixed-size arrays");
      const auto dimensions = type.GetFixedSizeArrayDimensions();
      const bool bytes = type.GetName() == "byte";
      info = bytes ? TypeInfo{"u8", ""} : Named(type);
      for (size_t i = dimensions.size(); i-- > 0;) {
        const string count = std::to_string(dimensions[i]);
        const bool packed = bytes && i + 1 == dimensions.size();
        info.codec = packed ? "c.FixedByteArray(" + count + ")"
                            : "c.FixedArray(" + info.codec + ", " + count + ")";
        info.zig = "[" + count + "]" + info.zig;
      }
      return info;
    }
    if (type.IsDynamicArray()) {
      info = type.GetName() == "byte" ? TypeInfo{"[]const u8", "c.ByteArray", true}
                                      : Sequence(Element(type, type.IsNullable()));
    } else {
      info = Named(type);
    }
    if (type.IsHeapNullable()) {
      return {"?*const " + info.zig, "c.Boxed(" + info.codec + ")", true};
    }
    const bool handle = info.codec == "c.Binder" || info.codec == "c.File" ||
                        info.codec.rfind("c.Interface(", 0) == 0;
    return type.IsNullable() || (field && handle) ? Optional(info) : info;
  }

  // The value AIDL gives a field that declares none.
  static string Default(const TypeInfo& info) {
    if (info.zig[0] == '?') return "null";
    if (info.zig.rfind("[]", 0) == 0) return "&.{}";
    return "aidl.defaultValue(" + info.zig + ")";
  }

  static string Literal(const AidlTypeSpecifier& type,
                        const std::variant<string, std::vector<string>>& raw) {
    if (type.IsArray()) {
      const string values = Join(std::get<std::vector<string>>(raw), ", ");
      return (type.IsFixedSizeArray() ? "." : "&.") + string("{ ") + values + " }";
    }
    string value = std::get<string>(raw);
    if (type.GetDefinedType() != nullptr) return "." + Identifier(value.substr(value.rfind('.') + 1));
    if ((type.GetName() == "float" || type.GetName() == "double") && !value.empty() &&
        (value.back() == 'f' || value.back() == 'F')) {
      value.pop_back();
    }
    return value;
  }

  void Constants(const AidlDefinedType& type) {
    for (const auto& constant : type.GetConstantDeclarations()) {
      const TypeInfo info = TypeOf(constant->GetType());
      out_ << "pub const " << Identifier(constant->GetName()) << ": " << info.zig << " = "
           << constant->ValueString(Literal) << ";\n";
    }
  }

  void Nested(const AidlDefinedType& type) {
    for (const auto& nested : type.GetNestedTypes()) Type(*nested);
  }

  // `.{ "name", codec }` rows the runtime iterates at comptime.
  void Table(const string& name, const std::vector<std::pair<string, TypeInfo>>& rows) {
    out_ << "pub const " << name << " = .{" << (rows.empty() ? "" : "\n");
    out_.Indent();
    for (const auto& [row, info] : rows) out_ << ".{ \"" << row << "\", " << info.codec << " },\n";
    out_.Dedent();
    out_ << "};\n";
  }

  void Record(const string& name, const std::vector<std::pair<string, TypeInfo>>& rows) {
    out_ << "pub const " << name << " = struct {" << (rows.empty() ? "" : "\n");
    out_.Indent();
    for (const auto& [row, info] : rows) out_ << Identifier(row) << ": " << info.zig << ",\n";
    out_.Dedent();
    out_ << "};\n";
  }

  void Interface(const AidlInterface& iface) {
    const string self = Identifier(iface.GetName());
    out_ << "pub const " << self << " = struct {\n";
    out_.Indent();
    out_ << "pub const descriptor = \"" << iface.GetDescriptor() << "\";\n";
    Constants(iface);
    Nested(iface);

    std::vector<const AidlMethod*> methods;
    for (const auto& method : iface.GetMethods()) {
      // getInterfaceVersion/getInterfaceHash are answered by stock backends only.
      if (method->IsUserDefined()) methods.push_back(method.get());
    }

    struct Signature {
      std::vector<std::pair<string, TypeInfo>> args, reply;
    };
    std::map<const AidlMethod*, Signature> signatures;

    out_ << "\npub const method = struct {\n";
    out_.Indent();
    for (const AidlMethod* method : methods) {
      Signature& signature = signatures[method];
      if (method->GetType().GetName() != "void") {
        signature.reply.emplace_back("result", TypeOf(method->GetType()));
      }
      for (const auto& argument : method->GetArguments()) {
        const TypeInfo info = TypeOf(argument->GetType());
        if (argument->IsIn()) {
          signature.args.emplace_back(argument->GetName(), info);
        } else if (argument->GetType().IsDynamicArray()) {
          // The proxy tells the server how long an `out` array it expects.
          signature.args.emplace_back(argument->GetName() + "_length", TypeInfo{"i32", "c.Int"});
        }
        if (argument->IsOut()) signature.reply.emplace_back(argument->GetName(), info);
      }
      out_ << "pub const " << Identifier(method->GetName()) << " = struct {\n";
      out_.Indent();
      out_ << "pub const name = \"" << method->GetName() << "\";\n";
      out_ << "pub const code: u32 = " << std::to_string(kFirstCallTransaction + method->GetId())
           << ";\n";
      out_ << "pub const oneway = " << (method->IsOneway() ? "true" : "false") << ";\n";
      Record("Args", signature.args);
      Record("Reply", signature.reply);
      Table("args", signature.args);
      Table("reply", signature.reply);
      out_.Dedent();
      out_ << "};\n";
    }
    out_.Dedent();
    out_ << "};\n";

    out_ << "pub const methods = .{";
    for (const AidlMethod* method : methods) out_ << " method." << Identifier(method->GetName()) << ",";
    out_ << " };\n\n";

    out_ << "/// A proxy. `remote` is a strong reference this value owns.\n";
    out_ << "pub const Client = struct {\n";
    out_.Indent();
    out_ << "remote: aidl.Binder,\n\n";
    out_ << "pub fn fromBinder(remote: aidl.Binder) aidl.Error!Client {\n";
    out_.Indent();
    out_ << "try aidl.associate(" << self << ", remote);\n";
    out_ << "return .{ .remote = remote };\n";
    out_.Dedent();
    out_ << "}\n";
    out_ << "pub fn deinit(self: Client) void {\n";
    out_.Indent();
    out_ << "self.remote.deinit();\n";
    out_.Dedent();
    out_ << "}\n";
    for (const AidlMethod* method : methods) {
      const Signature& signature = signatures[method];
      bool allocates = false;
      for (const auto& [name, info] : signature.reply) allocates |= info.allocates;
      const string declaration = "method." + Identifier(method->GetName());
      out_ << "pub fn " << Identifier(method->GetName()) << "(self: Client";
      if (allocates) out_ << ", allocator: aidl.Allocator";
      for (const auto& [name, info] : signature.args) {
        out_ << ", " << Identifier("_" + name) << ": " << info.zig;
      }
      out_ << ") aidl.Error!aidl.ReplyOf(" << declaration << ") {\n";
      out_.Indent();
      out_ << "return aidl.call(" << declaration << ", self.remote, "
           << (allocates ? "allocator" : "aidl.no_allocator") << ", .{";
      for (const auto& [name, info] : signature.args) {
        out_ << " ." << Identifier(name) << " = " << Identifier("_" + name) << ",";
      }
      out_ << " });\n";
      out_.Dedent();
      out_ << "}\n";
    }
    out_.Dedent();
    out_ << "};\n\n";

    out_ << "/// The Binder object serving this interface from `Implementation`.\n";
    out_ << "pub fn Server(comptime Implementation: type) type {\n";
    out_.Indent();
    out_ << "return aidl.Server(" << self << ", Implementation);\n";
    out_.Dedent();
    out_ << "}\n";
    out_.Dedent();
    out_ << "};\n";
  }

  void Parcelable(const AidlStructuredParcelable& parcelable) {
    out_ << "pub const " << Identifier(parcelable.GetName()) << " = struct {\n";
    out_.Indent();
    std::vector<std::pair<string, TypeInfo>> fields;
    for (const auto& field : parcelable.GetFields()) {
      const TypeInfo info = TypeOf(field->GetType(), /*field=*/true);
      fields.emplace_back(field->GetName(), info);
      out_ << Identifier(field->GetName()) << ": " << info.zig << " = ";
      if (field->GetDefaultValue() != nullptr && !field->GetType().IsNullable()) {
        out_ << field->ValueString(Literal);
      } else {
        out_ << Default(info);
      }
      out_ << ",\n";
    }
    out_ << "\n";
    Table("aidl_fields", fields);
    Constants(parcelable);
    Nested(parcelable);
    out_.Dedent();
    out_ << "};\n";
  }

  void Union(const AidlUnionDecl& alternatives) {
    const string self = Identifier(alternatives.GetName());
    out_ << "pub const " << self << " = union(enum) {\n";
    out_.Indent();
    std::vector<std::pair<string, TypeInfo>> fields;
    for (const auto& field : alternatives.GetFields()) {
      const TypeInfo info = TypeOf(field->GetType());
      fields.emplace_back(field->GetName(), info);
      out_ << Identifier(field->GetName()) << ": " << info.zig << ",\n";
    }
    out_ << "\n";
    Table("aidl_alternatives", fields);
    if (!fields.empty()) {
      // AIDL initializes a union to the default of its first alternative.
      const auto& first = *alternatives.GetFields().front();
      out_ << "pub const aidl_default: " << self << " = .{ ." << Identifier(first.GetName()) << " = ";
      if (first.GetDefaultValue() != nullptr) {
        out_ << first.ValueString(Literal);
      } else {
        out_ << Default(fields.front().second);
      }
      out_ << " };\n";
    }
    Constants(alternatives);
    Nested(alternatives);
    out_.Dedent();
    out_ << "};\n";
  }

  void Enum(const AidlEnumDeclaration& enumeration) {
    const string self = Identifier(enumeration.GetName());
    const TypeInfo backing = TypeOf(enumeration.GetBackingType());
    out_ << "pub const " << self << " = enum(" << backing.zig << ") {\n";
    out_.Indent();
    // Zig tags are unique; a repeated AIDL value becomes a named constant.
    std::map<string, string> seen;
    std::ostringstream aliases;
    for (const auto& enumerator : enumeration.GetEnumerators()) {
      const string value =
          enumerator->ValueString(enumeration.GetBackingType(), AidlConstantValueDecorator);
      const string name = Identifier(enumerator->GetName());
      if (auto [first, fresh] = seen.emplace(value, name); fresh) {
        out_ << name << " = " << value << ",\n";
      } else {
        aliases << "pub const " << name << ": " << self << " = ." << first->second << ";\n";
      }
    }
    out_ << "_,\n" << aliases.str();
    out_.Dedent();
    out_ << "};\n";
  }

  Unit& unit_;
  CodeWriter& out_;
  bool ok_ = true;
};

}  // namespace

bool GenerateZig(const Options& options, const AidlTypenames&, const AidlDefinedType& defined_type,
                 const IoDelegate& io_delegate) {
  if (defined_type.AsUnstructuredParcelable() != nullptr) return true;

  Unit unit(defined_type);
  string body;
  {
    CodeWriterPtr writer = CodeWriter::ForString(&body);
    Generator generator(unit, *writer);
    generator.Type(defined_type);
    writer->Close();
    if (!generator.Ok()) return false;
  }

  string path = options.OutputDir();
  for (const string& part : Split(defined_type.GetCanonicalName(), ".")) {
    path += part + OS_PATH_SEPARATOR;
  }
  path.pop_back();
  CodeWriterPtr out = io_delegate.GetCodeWriter(path + ".zig");
  *out << "// This file is auto-generated by `aidl --lang=zig`. DO NOT MODIFY.\n";
  *out << "const aidl = @import(\"aidl\");\n";
  *out << "const c = aidl.codec;\n";
  if (unit.UsesCustom()) *out << "const aidl_custom = @import(\"aidl_custom\");\n";
  for (const auto& [alias, import] : unit.Imports()) {
    *out << "const " << alias << " = " << import << ";\n";
  }
  *out << "\n" << body;
  AIDL_FATAL_IF(!out->Close(), defined_type) << "I/O Error!";
  return true;
}

}  // namespace zig
}  // namespace aidl
}  // namespace android
