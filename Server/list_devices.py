import pyaudiowpatch as pyaudio

p = pyaudio.PyAudio()

print("=" * 60)
print("ALL AUDIO DEVICES")
print("=" * 60)

for i in range(p.get_device_count()):
    dev = p.get_device_info_by_index(i)
    dev_type = []
    if dev['maxInputChannels'] > 0:
        dev_type.append("INPUT")
    if dev['maxOutputChannels'] > 0:
        dev_type.append("OUTPUT")
    if dev.get('isLoopbackDevice'):
        dev_type.append("LOOPBACK")

    print(f"[{i:02d}] {dev['name']}")
    print(f"      Type      : {' | '.join(dev_type)}")
    print(f"      Rate      : {int(dev['defaultSampleRate'])} Hz")
    print(f"      In/Out Ch : {dev['maxInputChannels']} in / {dev['maxOutputChannels']} out")
    print()

p.terminate()