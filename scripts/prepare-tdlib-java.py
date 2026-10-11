#!/usr/bin/env python3
"""Exclude upstream demonstration code, not bindings, from pinned Java/JNI build."""
import hashlib
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

def prepare(source, portable=False):
    path = pathlib.Path(source) / 'example/java/CMakeLists.txt'
    config = json.loads((ROOT / 'config/tdlib.json').read_text())
    original_hash = config['javaCmakeSha256']
    old = b'${JAVA_EXAMPLE_FILES} ${JAVA_SOURCE_FILES}'
    new = b'${JAVA_SOURCE_FILES}'
    content = path.read_bytes()
    jvm_link = b'target_link_libraries(tdjni PRIVATE ${JAVA_JVM_LIBRARY})'
    portable_link = b'# hradar portable JNI: JVM supplies JNIEnv; no external libjvm link'
    if portable and portable_link in content:
        content = content.replace(portable_link, jvm_link)
    if hashlib.sha256(content).hexdigest() == original_hash:
        if content.count(old) != 1:
            raise ValueError('Unexpected javac command; review pinned upstream CMake')
        content = content.replace(old, new)
        path.write_bytes(content)
        print('Excluded demonstration Example.java; Client.java, generated TdApi.java and JNI targets retained')
    elif content.count(old) == 0 and content.count(new) >= 1:
        restored = content.replace(b'"${JAVA_OUTPUT_DIRECTORY}" ' + new,
                                   b'"${JAVA_OUTPUT_DIRECTORY}" ' + old)
        if hashlib.sha256(restored).hexdigest() != original_hash:
            raise ValueError('Unexpected CMake changes; refusing compatibility patch')
        print('Java bindings-only compatibility patch already applied')
    else:
        raise ValueError('Unexpected upstream CMake hash; refusing compatibility patch')

    if portable:
        if content.count(jvm_link) != 1:
            raise ValueError('Unexpected JVM link target')
        path.write_bytes(content.replace(jvm_link, portable_link))

if __name__ == '__main__':
    try:
        prepare(sys.argv[1], portable="--portable" in sys.argv[2:])
    except (IndexError, ValueError, OSError) as exc:
        sys.exit(str(exc))
