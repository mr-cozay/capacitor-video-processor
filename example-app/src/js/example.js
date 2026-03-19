import { CapacitorVideoProcessorPlugin } from 'capacitor-video-processor';

window.testEcho = () => {
    const inputValue = document.getElementById("echoInput").value;
    CapacitorVideoProcessorPlugin.echo({ value: inputValue })
}
