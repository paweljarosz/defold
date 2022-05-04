b'#!/usr/bin/env python\nLICENSE="""\n// Copyright 2020-2022 The Defold Foundation\n// Copyright 2014-2020 King\n// Copyright 2009-2014 Ragnar Svensson, Christian Murray\n// Licensed under the Defold License version 1.0 (the "License"); you may not use\n// this file except in compliance with the License.\n//\n// You may obtain a copy of the License, together with FAQs at\n// https://www.defold.com/license\n//\n// Unless required by applicable law or agreed to in writing, software distributed\n// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR\n// CONDITIONS OF ANY KIND, either express or implied. See the License for the\n// specific language governing permissions and limitations under the License.\n"""\n\n\nfrom optparse import OptionParser\n\nimport sys, os, struct\nif sys.platform == \'win32\':\n    import msvcrt\n    msvcrt.setmode(sys.stdin.fileno(), os.O_BINARY)\n    msvcrt.setmode(sys.stdout.fileno(), os.O_BINARY)\n\nfrom io import StringIO\nimport dlib\n\nfrom google.protobuf.descriptor import FieldDescriptor\nfrom google.protobuf.descriptor_pb2 import FileDescriptorSet\nfrom google.protobuf.descriptor_pb2 import FieldDescriptorProto\n\nfrom plugin_pb2 import CodeGeneratorRequest, CodeGeneratorResponse\n\nimport ddf.ddf_extensions_pb2\n\nDDF_MAJOR_VERSION=1\nDDF_MINOR_VERSION=0\n\nDDF_POINTER_SIZE = 4\n\ntype_to_ctype = { FieldDescriptor.TYPE_DOUBLE : "double",\n                  FieldDescriptor.TYPE_FLOAT : "float",\n                  FieldDescriptor.TYPE_INT64 : "int64_t",\n                  FieldDescriptor.TYPE_UINT64 : "uint64_t",\n                  FieldDescriptor.TYPE_INT32 : "int32_t",\n#                  FieldDescriptor.TYPE_FIXED64\n#                  FieldDescriptor.TYPE_FIXED32\n                  FieldDescriptor.TYPE_BOOL : "bool",\n                  FieldDescriptor.TYPE_STRING : "const char*",\n#                  FieldDescriptor.TYPE_GROUP\n#                  FieldDescriptor.TYPE_MESSAGE\n#                  FieldDescriptor.TYPE_BYTES\n                  FieldDescriptor.TYPE_UINT32 : "uint32_t",\n#                  FieldDescriptor.TYPE_ENUM\n#                  FieldDescriptor.TYPE_SFIXED32\n#                  FieldDescriptor.TYPE_SFIXED64\n                  FieldDescriptor.TYPE_SINT32 : "int32_t",\n                  FieldDescriptor.TYPE_SINT64 : "int64_t" }\n\ntype_to_javatype = { FieldDescriptor.TYPE_DOUBLE : "double",\n                  FieldDescriptor.TYPE_FLOAT : "float",\n                  FieldDescriptor.TYPE_INT64 : "long",\n                  FieldDescriptor.TYPE_UINT64 : "long",\n                  FieldDescriptor.TYPE_INT32 : "int",\n#                  FieldDescriptor.TYPE_FIXED64\n#                  FieldDescriptor.TYPE_FIXED32\n                  FieldDescriptor.TYPE_BOOL : "boolean",\n                  FieldDescriptor.TYPE_STRING : "String",\n#                  FieldDescriptor.TYPE_GROUP\n#                  FieldDescriptor.TYPE_MESSAGE\n#                  FieldDescriptor.TYPE_BYTES\n                  FieldDescriptor.TYPE_UINT32 : "int",\n#                  FieldDescriptor.TYPE_ENUM\n#                  FieldDescriptor.TYPE_SFIXED32\n#                  FieldDescriptor.TYPE_SFIXED64\n                  FieldDescriptor.TYPE_SINT32 : "int",\n                  FieldDescriptor.TYPE_SINT64 : "long" }\n\ntype_to_boxedjavatype = { FieldDescriptor.TYPE_DOUBLE : "Double",\n                  FieldDescriptor.TYPE_FLOAT : "Float",\n                  FieldDescriptor.TYPE_INT64 : "Long",\n                  FieldDescriptor.TYPE_UINT64 : "Long",\n                  FieldDescriptor.TYPE_INT32 : "Integer",\n#                  FieldDescriptor.TYPE_FIXED64\n#                  FieldDescriptor.TYPE_FIXED32\n                  FieldDescriptor.TYPE_BOOL : "Boolean",\n                  FieldDescriptor.TYPE_STRING : "String",\n#                  FieldDescriptor.TYPE_GROUP\n#                  FieldDescriptor.TYPE_MESSAGE\n#                  FieldDescriptor.TYPE_BYTES\n                  FieldDescriptor.TYPE_UINT32 : "Integer",\n#                  FieldDescriptor.TYPE_ENUM\n#                  FieldDescriptor.TYPE_SFIXED32\n#                  FieldDescriptor.TYPE_SFIXED64\n                  FieldDescriptor.TYPE_SINT32 : "Integer",\n                  FieldDescriptor.TYPE_SINT64 : "Long" }\n\ntype_to_struct_format = { FieldDescriptor.TYPE_DOUBLE : ("d", float),\n                          FieldDescriptor.TYPE_FLOAT : ("f", float),\n                          FieldDescriptor.TYPE_INT64 : ("q", int),\n                          FieldDescriptor.TYPE_UINT64 : ("Q", int),\n                          FieldDescriptor.TYPE_INT32 : ("i", int),\n#                         FieldDescriptor.TYPE_FIXED64\n#                         FieldDescriptor.TYPE_FIXED32\n                          FieldDescriptor.TYPE_BOOL : ("?", bool),\n#                         FieldDescriptor.TYPE_STRING  NOTE: String is a special case\n#                         FieldDescriptor.TYPE_GROUP\n#                         FieldDescriptor.TYPE_MESSAGE\n#                         FieldDescriptor.TYPE_BYTES\n                          FieldDescriptor.TYPE_UINT32 : ("I", int),\n                          FieldDescriptor.TYPE_ENUM : ("i", int),\n#                         FieldDescriptor.TYPE_SFIXED32\n#                         FieldDescriptor.TYPE_SFIXED64\n                          FieldDescriptor.TYPE_SINT32 : ("i", int),\n                          FieldDescriptor.TYPE_SINT64 : ("q", int) }\n\nclass PrettyPrinter(object):\n    def __init__(self, stream, initial_indent = 0):\n        self.stream = stream\n        self.indent = initial_indent\n\n    def begin(self, str, *format):\n        str = str % format\n        print >>self.stream, " " * self.indent + str\n        print >>self.stream, " " * self.indent + "{"\n        self.indent += 4\n\n    def begin_paren(self, str, *format):\n        str = str % format\n        print >>self.stream, " " * self.indent + str\n        print >>self.stream, " " * self.indent + "("\n        self.indent += 4\n\n    def p(self, str, *format):\n        str = str % format\n        print >>self.stream, " " * self.indent + str\n\n    def end(self, str = "", *format):\n        str = str % format\n        self.indent -= 4\n        print >>self.stream, " " * self.indent + "}%s;" % str\n        print >>self.stream, ""\n\n    def end_paren(self, str = "", *format):\n        str = str % format\n        self.indent -= 4\n        print >>self.stream, " " * self.indent + ")%s;" % str\n        print >>self.stream, ""\n\ndef to_camel_case(name, initial_capital = True):\n    """Returns a camel-case separated format of the supplied name, which is supposed to be lower-case separated by under-score:\n    >>> to_camel_case("abc_abc01_abc")\n    "AbcAbc01Abc"\n    """\n    words = name.lower().split(\'_\')\n    for i in range(len(words)):\n        if (initial_capital or (i != 0)):\n            words[i] = words[i].capitalize()\n    return \'\'.join(words)\n\ndef to_lower_case(name):\n    """Returns a lower-case under-score separated format of the supplied name, which is supposed to be camel-case:\n    >>> ToScriptName("AbcABC01ABCAbc")\n    "abc_abc01abc_abc"\n    """\n    script_name = ""\n    for i in range(len(name)):\n        if i > 0 and (name[i-1:i].islower() or name[i-1:i].isdigit() or (i+1 < len(name) and name[i+1:i+2].islower())) and name[i:i+1].isupper():\n            script_name += "_"\n        script_name += name[i:i+1].lower()\n    return script_name\n\ndef dot_to_cxx_namespace(str):\n    if str.startswith("."):\n        str = str[1:]\n    return str.replace(".", "::")\n\ndef to_cxx_struct(context, pp, message_type):\n    # Calculate maximum length of "type"\n    max_len = 0\n    for f in message_type.field:\n        l = 0\n        align_str = ""\n        if context.should_align_field(f):\n            align_str = "DM_ALIGNED(16) "\n\n        if f.label == FieldDescriptor.LABEL_REPEATED:\n            pass\n        elif f.type  == FieldDescriptor.TYPE_BYTES:\n            pass\n        elif f.type == FieldDescriptor.TYPE_ENUM or f.type == FieldDescriptor.TYPE_MESSAGE:\n            l += len(align_str + context.get_field_type_name(f))\n        else:\n            l += len(align_str + type_to_ctype[f.type])\n\n        max_len = max(l, max_len)\n\n    def p(t, n):\n        pp.p("%s%sm_%s;", t, (max_len-len(t) + 1) * " ",n)\n\n    if (context.should_align_struct(message_type)):\n        pp.begin("struct DM_ALIGNED(16) %s", message_type.name)\n    else:\n        pp.begin("struct %s", message_type.name)\n\n    for et in message_type.enum_type:\n        to_cxx_enum(context, pp, et)\n\n    for nt in message_type.nested_type:\n        to_cxx_struct(context, pp, nt)\n\n    for f in message_type.field:\n        field_name = to_camel_case(f.name)\n        field_align = context.should_align_field(f)\n        align_str = ""\n        if (field_align):\n            align_str = "DM_ALIGNED(16) "\n        if f.label == FieldDescriptor.LABEL_REPEATED or f.type == FieldDescriptor.TYPE_BYTES:\n            if (field_align):\n                pp.begin("struct DM_ALIGNED(16)")\n            else:\n                pp.begin("struct")\n\n            if f.type ==  FieldDescriptor.TYPE_MESSAGE:\n                type_name = dot_to_cxx_namespace(f.type_name)\n            elif f.type ==  FieldDescriptor.TYPE_BYTES:\n                type_name = align_str + "uint8_t"\n            else:\n                type_name = align_str + type_to_ctype[f.type]\n\n            pp.p(type_name+"* m_Data;")\n\n            if f.type == FieldDescriptor.TYPE_STRING:\n                pp.p("%s operator[](uint32_t i) const { assert(i < m_Count); return m_Data[i]; }", type_name)\n            else:\n                pp.p("const %s& operator[](uint32_t i) const { assert(i < m_Count); return m_Data[i]; }", type_name)\n                pp.p("%s& operator[](uint32_t i) { assert(i < m_Count); return m_Data[i]; }", type_name)\n\n            pp.p("uint32_t " + "m_Count;")\n            pp.end(" m_%s", field_name)\n        elif f.type ==  FieldDescriptor.TYPE_ENUM or f.type == FieldDescriptor.TYPE_MESSAGE:\n            p(align_str + context.get_field_type_name(f), field_name)\n        else:\n            p(align_str + type_to_ctype[f.type], field_name)\n    pp.p(\'\')\n    pp.p(\'static dmDDF::Descriptor* m_DDFDescriptor;\')\n    pp.p(\'static const uint64_t m_DDFHash;\')\n    pp.end()\n\ndef to_cxx_enum(context, pp, message_type):\n    lst = []\n    for f in message_type.value:\n        lst.append((f.name, f.number))\n\n    max_len = reduce(lambda y,x: max(len(x[0]), y), lst, 0)\n    pp.begin("enum %s",  message_type.name)\n\n    for t,n in lst:\n        pp.p("%s%s= %d,", t, (max_len-len(t) + 1) * " ",n)\n\n    pp.end()\n\ndef to_cxx_default_value_string(context, f):\n    # Skip all empty values. Type string is an exception as we always set these to ""\n    if len(f.default_value) == 0 and f.type != FieldDescriptor.TYPE_STRING:\n        return \'\'\n    else:\n        if f.type == FieldDescriptor.TYPE_STRING:\n            return \'"%s\\\\x00"\' % \'\'.join(map(lambda x: \'\\\\x%02x\' % ord(x), f.default_value))\n        elif f.type == FieldDescriptor.TYPE_ENUM:\n            default_value = None\n            for e in context.message_types[f.type_name].value:\n                if e.name == f.default_value:\n                    default_value = e.number\n                    break\n            if default_value == None:\n                raise Exception("Default \'%s\' not found" % f.default_value)\n\n            form, func = type_to_struct_format[f.type]\n            # Store in little endian\n            tmp = struct.pack(\'<\' + form, func(default_value))\n            return \'"%s"\' % \'\'.join(map(lambda x: \'\\\\x%02x\' % ord(x), tmp))\n        elif f.type == FieldDescriptor.TYPE_BOOL:\n            if f.default_value == "true":\n                return \'"\\\\x01"\'\n            else:\n                return \'"\\\\x00"\'\n        else:\n            form, func = type_to_struct_format[f.type]\n            # Store in little endian\n            tmp = struct.pack(\'<\' + form, func(f.default_value))\n            return \'"%s"\' % \'\'.join(map(lambda x: \'\\\\x%02x\' % ord(x), tmp))\n\ndef to_cxx_descriptor(context, pp_cpp, pp_h, message_type, namespace_lst):\n    namespace = "_".join(namespace_lst)\n    pp_h.p(\'extern dmDDF::Descriptor %s_%s_DESCRIPTOR;\', namespace, message_type.name)\n\n    for nt in message_type.nested_type:\n        to_cxx_descriptor(context, pp_cpp, pp_h, nt, namespace_lst + [message_type.name] )\n\n    for f in message_type.field:\n        default = to_cxx_default_value_string(context, f)\n        if \'"\' in default:\n            pp_cpp.p(\'char DM_ALIGNED(4) %s_%s_%s_DEFAULT_VALUE[] = %s;\', namespace, message_type.name, f.name, default)\n\n    lst = []\n    for f in message_type.field:\n        tpl = (f.name, f.number, f.type, f.label)\n        if f.type ==  FieldDescriptor.TYPE_MESSAGE:\n            tmp = f.type_name.replace(".", "_")\n            if tmp.startswith("_"):\n                tmp = tmp[1:]\n            tpl += ("&" + tmp + "_DESCRIPTOR", )\n        else:\n            tpl += ("0x0", )\n\n        tpl += ("(uint32_t)DDF_OFFSET_OF(%s::%s, m_%s)" % (namespace.replace("_", "::"), message_type.name, to_camel_case(f.name)), )\n\n        default = to_cxx_default_value_string(context, f)\n        if \'"\' in default:\n            tpl += (\'%s_%s_%s_DEFAULT_VALUE\' % (namespace, message_type.name, f.name), )\n        else:\n            tpl += (\'0x0\',)\n\n        lst.append(tpl)\n\n    if len(lst) > 0:\n        pp_cpp.begin("dmDDF::FieldDescriptor %s_%s_FIELDS_DESCRIPTOR[] = ", namespace, message_type.name)\n        for name, number, type, label, msg_desc, offset, default_value in lst:\n            pp_cpp.p(\'{ "%s", %d, %d, %d, %s, %s, %s},\'  % (name, number, type, label, msg_desc, offset, default_value))\n        pp_cpp.end()\n    else:\n        pp_cpp.p("dmDDF::FieldDescriptor* %s_%s_FIELDS_DESCRIPTOR = 0x0;", namespace, message_type.name)\n\n    pp_cpp.begin("dmDDF::Descriptor %s_%s_DESCRIPTOR = ", namespace, message_type.name)\n    pp_cpp.p(\'%d, %d,\', DDF_MAJOR_VERSION, DDF_MINOR_VERSION)\n    pp_cpp.p(\'"%s",\', to_lower_case(message_type.name))\n    pp_cpp.p(\'0x%016XULL,\', dlib.dmHashBuffer64(to_lower_case(message_type.name)))\n    pp_cpp.p(\'sizeof(%s::%s),\', namespace.replace("_", "::"), message_type.name)\n    pp_cpp.p(\'%s_%s_FIELDS_DESCRIPTOR,\', namespace, message_type.name)\n    if len(lst) > 0:\n        pp_cpp.p(\'sizeof(%s_%s_FIELDS_DESCRIPTOR)/sizeof(dmDDF::FieldDescriptor),\', namespace, message_type.name)\n    else:\n        pp_cpp.p(\'0,\')\n    pp_cpp.end()\n\n    pp_cpp.p(\'dmDDF::Descriptor* %s::%s::m_DDFDescriptor = &%s_%s_DESCRIPTOR;\' % (\'::\'.join(namespace_lst), message_type.name, namespace, message_type.name))\n\n    # TODO: This is not optimal. Hash value is sensitive on googles format string\n    # Also dependent on type invariant values?\n    hash_string = str(message_type).replace(" ", "").replace("\\n", "").replace("\\r", "")\n    pp_cpp.p(\'const uint64_t %s::%s::m_DDFHash = 0x%016XULL;\' % (\'::\'.join(namespace_lst), message_type.name, dlib.dmHashBuffer64(hash_string)))\n\n    pp_cpp.p(\'dmDDF::InternalRegisterDescriptor g_Register_%s_%s(&%s_%s_DESCRIPTOR);\' % (namespace, message_type.name, namespace, message_type.name))\n\n    pp_cpp.p(\'\')\n\ndef to_cxx_enum_descriptor(context, pp_cpp, pp_h, enum_type, namespace_lst):\n    namespace = "_".join(namespace_lst)\n\n    pp_h.p("extern dmDDF::EnumDescriptor %s_%s_DESCRIPTOR;", namespace, enum_type.name)\n\n    lst = []\n    for f in enum_type.value:\n        tpl = (f.name, f.number)\n        lst.append(tpl)\n\n    pp_cpp.begin("dmDDF::EnumValueDescriptor %s_%s_FIELDS_DESCRIPTOR[] = ", namespace, enum_type.name)\n\n    for name, number in lst:\n        pp_cpp.p(\'{ "%s", %d },\'  % (name, number))\n\n    pp_cpp.end()\n\n    pp_cpp.begin("dmDDF::EnumDescriptor %s_%s_DESCRIPTOR = ", namespace, enum_type.name)\n    pp_cpp.p(\'%d, %d,\', DDF_MAJOR_VERSION, DDF_MINOR_VERSION)\n    pp_cpp.p(\'"%s",\', to_lower_case(enum_type.name))\n    pp_cpp.p(\'%s_%s_FIELDS_DESCRIPTOR,\', namespace, enum_type.name)\n    pp_cpp.p(\'sizeof(%s_%s_FIELDS_DESCRIPTOR)/sizeof(dmDDF::EnumValueDescriptor),\', namespace, enum_type.name)\n    pp_cpp.end()\n\ndef dot_to_java_package(context, str, proto_package, java_package):\n    if str.startswith("."):\n        str = str[1:]\n\n    ret = context.type_name_to_java_type[str]\n    ret = ret.replace(java_package + \'.\', "")\n    return ret\n\ndef to_java_enum(context, pp, message_type):\n    lst = []\n    for f in message_type.value:\n        lst.append(("public static final int %s" % (f.name), f.number))\n\n    max_len = reduce(lambda y,x: max(len(x[0]), y), lst, 0)\n    #pp.begin("enum %s",  message_type.name)\n\n    for t,n in lst:\n        pp.p("%s%s= %d;", t, (max_len-len(t) + 1) * " ",n)\n    pp.p("")\n    #pp.end()\n\ndef to_java_enum_descriptor(context, pp, enum_type, qualified_proto_package):\n\n    lst = []\n    for f in enum_type.value:\n        tpl = (f.name, f.number)\n        lst.append(tpl)\n\n    pp.begin(\'public static class %s\' % enum_type.name)\n    pp.begin("public static com.dynamo.ddf.EnumValueDescriptor VALUES_DESCRIPTOR[] = new com.dynamo.ddf.EnumValueDescriptor[]")\n\n    for name, number in lst:\n        pp.p(\'new com.dynamo.ddf.EnumValueDescriptor("%s", %d),\'  % (name, number))\n\n    pp.end()\n    name = qualified_proto_package.replace(\'$\', \'_\').replace(\'.\', \'_\')\n    pp.p(\'public static com.dynamo.ddf.EnumDescriptor DESCRIPTOR = new com.dynamo.ddf.EnumDescriptor("%s", VALUES_DESCRIPTOR);\' % (name))\n    pp.end()\n\ndef to_java_descriptor(context, pp, message_type, proto_package, java_package, qualified_proto_package):\n\n    lst = []\n    for f in message_type.field:\n        tpl = (to_camel_case(f.name, False), f.number, f.type, f.label)\n        f_type_name = f.type_name\n        if f.type ==  FieldDescriptor.TYPE_MESSAGE:\n            tmp = dot_to_java_package(context, f_type_name, proto_package, java_package)\n            if tmp.startswith("."):\n                tmp = tmp[1:]\n            tpl += (tmp + ".DESCRIPTOR", "null")\n        elif f.type ==  FieldDescriptor.TYPE_ENUM:\n            tmp = dot_to_java_package(context, f_type_name, proto_package, java_package)\n            if tmp.startswith("."):\n                tmp = tmp[1:]\n            tpl += ("null", tmp + ".DESCRIPTOR", )\n\n        else:\n            tpl += ("null", "null")\n        lst.append(tpl)\n\n    pp.begin("public static com.dynamo.ddf.FieldDescriptor FIELDS_DESCRIPTOR[] = new com.dynamo.ddf.FieldDescriptor[]")\n\n    for name, number, type, label, msg_desc, enum_desc in lst:\n        pp.p(\'new com.dynamo.ddf.FieldDescriptor("%s", %d, %d, %d, %s, %s),\'  % (name, number, type, label, msg_desc, enum_desc))\n\n    pp.end()\n\n    pp.begin_paren("public static com.dynamo.ddf.Descriptor DESCRIPTOR = new com.dynamo.ddf.Descriptor")\n    pp.p(\'"%s",\', qualified_proto_package.replace(\'$\', \'_\').replace(\'.\', \'_\'))\n    pp.p(\'FIELDS_DESCRIPTOR\')\n    pp.end_paren(\'\')\n\n    # TODO: Add hash support for java types?\n    # TODO: This is not optimal. Hash value is sensitive on googles format string\n    # Also dependent on type invariant values?\n    hash_string = str(message_type).replace(" ", "").replace("\\n", "").replace("\\r", "")\n    #pp.p(\'const uint64_t %s::%s::m_DDFHash = 0x%016XULL;\' % (\'::\'.join(namespace_lst), message_type.name, dlib.dmHashBuffer64(hash_string)))\n\ndef to_java_class(context, pp, message_type, proto_package, java_package, qualified_proto_package):\n    # Calculate maximum length of "type"\n    max_len = 0\n    for f in message_type.field:\n        if f.label == FieldDescriptor.LABEL_REPEATED:\n            if f.type ==  FieldDescriptor.TYPE_MESSAGE:\n                tn = dot_to_java_package(context, f.type_name, proto_package, java_package)\n            elif f.type == FieldDescriptor.TYPE_BYTES:\n                assert False\n            else:\n                tn = type_to_boxedjavatype[f.type]\n            max_len = max(len(\'List<%s>\' % tn), max_len)\n        elif f.type  == FieldDescriptor.TYPE_BYTES:\n            max_len = max(len("byte[]"), max_len)\n        elif f.type == FieldDescriptor.TYPE_ENUM:\n            max_len = max(len("int"), max_len)\n        elif f.type == FieldDescriptor.TYPE_MESSAGE:\n            max_len = max(len(dot_to_java_package(context, f.type_name, proto_package, java_package)), max_len)\n        else:\n            max_len = max(len(type_to_javatype[f.type]), max_len)\n\n    def p(t, n, assign = None):\n        if assign:\n            pp.p("public %s%s%s = %s;", t, (max_len-len(t) + 1) * " ", n, assign)\n        else:\n            pp.p("public %s%s%s;", t, (max_len-len(t) + 1) * " ", n)\n\n    pp.p(\'@ProtoClassName(name = "%s")\' % qualified_proto_package)\n    pp.begin("public static final class %s", message_type.name)\n\n    for et in message_type.enum_type:\n        to_java_enum_descriptor(context, pp, et, qualified_proto_package + \'_\' + et.name)\n        to_java_enum(context, pp, et)\n\n    to_java_descriptor(context, pp, message_type, proto_package, java_package, qualified_proto_package)\n\n    for nt in message_type.nested_type:\n        to_java_class(context, pp, nt, proto_package, java_package, qualified_proto_package + "$" + nt.name)\n\n    for f in message_type.field:\n        f_name = to_camel_case(f.name, False)\n        if f.label == FieldDescriptor.LABEL_REPEATED:\n            if f.type ==  FieldDescriptor.TYPE_MESSAGE:\n                type_name = dot_to_java_package(context, f.type_name, proto_package, java_package)\n            elif f.type ==  FieldDescriptor.TYPE_BYTES:\n                type_name = "Byte"\n            else:\n                type_name = type_to_boxedjavatype[f.type]\n\n            pp.p(\'@ComponentType(type = %s.class)\' % type_name)\n            p(\'List<%s>\' % (type_name), f_name, \'new ArrayList<%s>()\' % type_name)\n        elif f.type == FieldDescriptor.TYPE_BYTES:\n            p(\'byte[]\', f_name)\n        elif f.type == FieldDescriptor.TYPE_ENUM:\n            p("int", f_name)\n        elif f.type == FieldDescriptor.TYPE_MESSAGE:\n            java_type_name = dot_to_java_package(context, f.type_name, proto_package, java_package)\n            p(java_type_name, f_name, \'new %s()\' % java_type_name)\n        else:\n            p(type_to_javatype[f.type], f_name)\n    pp.end()\n\ndef compile_java(context, proto_file, ddf_java_package, file_to_generate):\n    file_desc = proto_file\n\n    path = ddf_java_package.replace(\'.\', \'/\')\n\n\n    file_java = context.response.file.add()\n    file_java.name = os.path.join(path, proto_file.options.java_outer_classname + \'.java\')\n    f_java = StringIO()\n\n    pp_java = PrettyPrinter(f_java, 0)\n\n    if ddf_java_package != \'\':\n        pp_java.p("package %s;",  ddf_java_package)\n    pp_java.p("")\n    pp_java.p("import java.util.List;")\n    pp_java.p("import java.util.ArrayList;")\n    pp_java.p("import com.dynamo.ddf.annotations.ComponentType;")\n    pp_java.p("import com.dynamo.ddf.annotations.ProtoClassName;")\n\n    pp_java.p("")\n    pp_java.begin("public final class %s", proto_file.options.java_outer_classname)\n\n    for mt in file_desc.enum_type:\n        to_java_enum_descriptor(context, pp_java, mt, ddf_java_package + \'_\' + mt.name)\n        to_java_enum(context, pp_java, mt)\n\n    for mt in file_desc.message_type:\n        to_java_class(context, pp_java, mt, file_desc.package,\n                    ddf_java_package + "." + proto_file.options.java_outer_classname,\n                    file_desc.options.java_package + "." + file_desc.options.java_outer_classname + "$" + mt.name)\n\n    pp_java.end("")\n\n    file_java.content = f_java.getvalue()\n\ndef to_ensure_struct_alias_size(context, file_desc, pp_cpp):\n    import hashlib\n    m = hashlib.md5(file_desc.package + file_desc.name)\n    pp_cpp.begin(\'void EnsureStructAliasSize_%s()\' % m.hexdigest())\n\n    for t, at in context.type_alias_messages.iteritems():\n        pp_cpp.p(\'DM_STATIC_ASSERT(sizeof(%s) == sizeof(%s), Invalid_Struct_Alias_Size);\' % (dot_to_cxx_namespace(t), at))\n\n    pp_cpp.end()\n\ndef compile_cxx(context, proto_file, file_to_generate, namespace, includes):\n    base_name = os.path.basename(file_to_generate)\n\n    if base_name.rfind(".") != -1:\n        base_name = base_name[0:base_name.rfind(".")]\n\n    file_desc = proto_file\n\n    file_h = context.response.file.add()\n    file_h.name = base_name + ".h"\n\n    f_h = StringIO()\n    pp_h = PrettyPrinter(f_h, 0)\n\n    pp_h.p(LICENSE)\n    pp_h.p("")\n    pp_h.p("// GENERATED FILE! DO NOT EDIT!");\n    pp_h.p("")\n\n    guard_name = base_name.upper() + "_H"\n    pp_h.p(\'#ifndef %s\', guard_name)\n    pp_h.p(\'#define %s\', guard_name)\n    pp_h.p("")\n\n    pp_h.p(\'#include <stdint.h>\')\n    pp_h.p(\'#include <assert.h>\')\n    pp_h.p(\'#include <ddf/ddf.h>\')\n    for d in file_desc.dependency:\n        if not \'ddf_extensions\' in d:\n            pp_h.p(\'#include "%s"\', d.replace(".proto", ".h"))\n    pp_h.p(\'#include <dmsdk/dlib/align.h>\')\n\n    for i in includes:\n        pp_h.p(\'#include "%s"\', i)\n    pp_h.p("")\n\n    if namespace:\n        pp_h.begin("namespace %s",  namespace)\n    pp_h.begin("namespace %s",  file_desc.package)\n\n    for mt in file_desc.enum_type:\n        to_cxx_enum(context, pp_h, mt)\n\n    for mt in file_desc.message_type:\n        to_cxx_struct(context, pp_h, mt)\n\n    pp_h.end()\n\n    file_cpp = context.response.file.add()\n    file_cpp.name = base_name + ".cpp"\n    f_cpp = StringIO()\n\n    pp_cpp = PrettyPrinter(f_cpp, 0)\n    pp_cpp.p(\'#include <ddf/ddf.h>\')\n    for d in file_desc.dependency:\n        if not \'ddf_extensions\' in d:\n            pp_cpp.p(\'#include "%s"\', d.replace(".proto", ".h"))\n    pp_cpp.p(\'#include "%s.h"\' % base_name)\n\n    if namespace:\n        pp_cpp.begin("namespace %s",  namespace)\n\n    pp_h.p("#ifdef DDF_EXPOSE_DESCRIPTORS")\n\n    for mt in file_desc.enum_type:\n        to_cxx_enum_descriptor(context, pp_cpp, pp_h, mt, [file_desc.package])\n\n    for mt in file_desc.message_type:\n        to_cxx_descriptor(context, pp_cpp, pp_h, mt, [file_desc.package])\n\n    pp_h.p("#endif")\n\n    if namespace:\n        pp_h.end()\n\n    to_ensure_struct_alias_size(context, file_desc, pp_cpp)\n\n    if namespace:\n        pp_cpp.end()\n\n    file_cpp.content = f_cpp.getvalue()\n\n    pp_h.p(\'#endif // %s \', guard_name)\n    file_h.content = f_h.getvalue()\n\n\nclass CompilerContext(object):\n    def __init__(self, request):\n        self.request = request\n        self.message_types = {}\n        self.type_name_to_java_type = {}\n        self.type_alias_messages = {}\n        self.response = CodeGeneratorResponse()\n\n    # TODO: We add enum types as message types. Kind of hack...\n    def add_message_type(self, package, java_package, java_outer_classname, message_type):\n        if message_type.name in self.message_types:\n            return\n        n = str(package + \'.\' + message_type.name)\n        self.message_types[n] = message_type\n\n        if self.has_type_alias(n):\n            self.type_alias_messages[n] = self.type_alias_name(n)\n\n        self.type_name_to_java_type[package[1:] + \'.\' + message_type.name] = java_package + \'.\' + java_outer_classname + \'.\' + message_type.name\n\n        if hasattr(message_type, \'nested_type\'):\n            for mt in message_type.nested_type:\n                # TODO: add something to java_package here?\n                self.add_message_type(package + \'.\' + message_type.name, java_package, java_outer_classname, mt)\n\n            for et in message_type.enum_type:\n                self.add_message_type(package + \'.\' + message_type.name, java_package, java_outer_classname, et)\n\n    def should_align_field(self, f):\n        for x in f.options.ListFields():\n            if x[0].name == \'field_align\':\n                return True\n        return False\n\n    def should_align_struct(self, mt):\n        for x in mt.options.ListFields():\n            if x[0].name == \'struct_align\':\n                return True\n        return False\n\n    def has_type_alias(self, type_name):\n        mt = self.message_types[type_name]\n        for x in mt.options.ListFields():\n            if x[0].name == \'alias\':\n                return True\n        return False\n\n    def type_alias_name(self, type_name):\n        mt = self.message_types[type_name]\n        for x in mt.options.ListFields():\n            if x[0].name == \'alias\':\n                return x[1]\n        assert False\n\n    def get_field_type_name(self, f):\n        if f.type ==  FieldDescriptor.TYPE_MESSAGE:\n            if self.has_type_alias(f.type_name):\n                return self.type_alias_name(f.type_name)\n            else:\n                return dot_to_cxx_namespace(f.type_name)\n        elif f.type == FieldDescriptor.TYPE_ENUM:\n            return dot_to_cxx_namespace(f.type_name)\n        else:\n            assert(False)\n\nif __name__ == \'__main__\':\n    import doctest\n\n    usage = "usage: %prog [options] FILE"\n    parser = OptionParser(usage = usage)\n    parser.add_option("-o", dest="output_file", help="Output file (.cpp)", metavar="FILE")\n    parser.add_option("--java", dest="java", help="Generate java", action="store_true")\n    parser.add_option("--cxx", dest="cxx", help="Generate c++", action="store_true")\n    (options, args) = parser.parse_args()\n\n    request = CodeGeneratorRequest()\n    request.ParseFromString(sys.stdin.read())\n    context = CompilerContext(request)\n\n    for pf in request.proto_file:\n        java_package = \'\'\n        for x in pf.options.ListFields():\n            if x[0].name == \'ddf_java_package\':\n                java_package = x[1]\n\n        for mt in pf.message_type:\n            context.add_message_type(\'.\' + pf.package, java_package, pf.options.java_outer_classname, mt)\n\n        for et in pf.enum_type:\n            # NOTE: We add enum types as message types. Kind of hack...\n            context.add_message_type(\'.\' + pf.package, java_package, pf.options.java_outer_classname, et)\n\n    for pf in request.proto_file:\n        if pf.name == request.file_to_generate[0]:\n            namespace = None\n            for x in pf.options.ListFields():\n                if x[0].name == \'ddf_namespace\':\n                    namespace = x[1]\n\n            includes = []\n            for x in pf.options.ListFields():\n                if x[0].name == \'ddf_includes\':\n                    includes = x[1].split()\n\n            if options.cxx:\n                compile_cxx(context, pf, request.file_to_generate[0], namespace, includes)\n\n            for x in pf.options.ListFields():\n                if x[0].name == \'ddf_java_package\':\n                    if options.java:\n                        compile_java(context, pf, x[1], request.file_to_generate[0])\n\n    sys.stdout.write(context.response.SerializeToString())\n'