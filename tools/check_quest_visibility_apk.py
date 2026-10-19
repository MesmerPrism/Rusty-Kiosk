"""Read-only DEX definition gate: references to vendor APIs are not definitions."""
import argparse
import json
import struct
import zipfile


def definitions(dex):
    if len(dex) < 112 or dex[:4] != b"dex\n":
        raise ValueError("Not a standard DEX file")

    def u32(at):
        return struct.unpack_from("<I", dex, at)[0]

    strings, types, count, classes = u32(60), u32(68), u32(96), u32(100)
    for index in range(count):
        type_index = u32(classes + index * 32)
        string_index = u32(types + type_index * 4)
        at = u32(strings + string_index * 4)
        # Bounded ULEB128 UTF-16 length. Descriptors below are ASCII.
        for _ in range(5):
            current = dex[at]
            at += 1
            if not current & 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        end = dex.index(0, at)
        yield dex[at:end].decode("utf-8", errors="surrogatepass")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    args = parser.parse_args()
    found = set()
    with zipfile.ZipFile(args.apk) as apk:
        for name in apk.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                found.update(definitions(apk.read(name)))
    forbidden = sorted(name for name in found if name.startswith("Lhorizonos/os/resource/")
                       or name == "Lio/github/mesmerprism/rustykiosk/VisibilityPlatformReader;"
                       or name.startswith("Lio/github/mesmerprism/rustyquest/shellrecenter/")
                       or name.startswith("Lio/github/mesmerprism/rustykiosk/shellcompanion/"))
    prefix = "Lio/github/mesmerprism/rustyquest/navigatorvisibility/"
    required = {prefix + name + ";" for name in (
        "NavigatorVisibilityObserver", "AndroidNavigatorVisibility", "CrcsVisibilityBackend")}
    missing = sorted(required - found)
    if forbidden or missing:
        raise ValueError(json.dumps({"forbidden_definitions": forbidden, "missing_component": missing}))
    print(json.dumps({"schema": "rusty.kiosk.quest_visibility_apk.v1", "result": "pass",
                      "component_definitions_present": True, "vendor_signatures_packaged": False}))


if __name__ == "__main__":
    main()
