import pyaudiowpatch as pyaudio

p = pyaudio.PyAudio()
wasapi = p.get_host_api_info_by_type(pyaudio.paWASAPI)

# Find default output and its loopback version
default_speakers = p.get_device_info_by_index(wasapi['defaultOutputDevice'])
print(f'Default output: {default_speakers["name"]}')

# Find loopback device for default speakers
for i in range(p.get_device_count()):
    dev = p.get_device_info_by_index(i)
    if dev.get('isLoopbackDevice') and default_speakers['name'] in dev['name']:
        print(f'Loopback found: {dev["name"]}')
        print(f'Sample Rate: {dev["defaultSampleRate"]}')
        print(f'Channels: {dev["maxInputChannels"]}')
        break

p.terminate()