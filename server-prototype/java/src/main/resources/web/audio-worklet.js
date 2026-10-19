class MonoPcmWorklet extends AudioWorkletProcessor {
  constructor() {
    super();
    this.active = false;
    this.output = new Int16Array(10240);
    this.used = 0;
    this.inputIndex = 0;
    this.nextOutput = 0;
    this.previous = 0;
    this.step = sampleRate / 16000;
    this.port.onmessage = event => {
      if (event.data === 'start') {
        this.active = true;
        this.used = 0;
        this.inputIndex = 0;
        this.nextOutput = 0;
        this.previous = 0;
      } else if (event.data === 'stop') {
        this.active = false;
        if (this.used) this.send();
        this.port.postMessage({type: 'stopped'});
      }
    };
  }
  send() {
    const buffer = this.output.buffer.slice(0, this.used * 2);
    this.port.postMessage({type: 'audio', buffer}, [buffer]);
    this.used = 0;
  }
  process(inputs, outputs) {
    const channel = inputs[0]?.[0];
    if (this.active && channel) {
      for (let i = 0; i < channel.length; i++) {
        const current = channel[i];
        while (this.nextOutput <= this.inputIndex) {
          const fraction = this.inputIndex ? Math.max(0, Math.min(1, this.nextOutput - (this.inputIndex - 1))) : 1;
          const value = this.previous + (current - this.previous) * fraction;
          this.output[this.used++] = Math.max(-32768, Math.min(32767, Math.round(value * 32767)));
          if (this.used === this.output.length) this.send();
          this.nextOutput += this.step;
        }
        this.previous = current;
        this.inputIndex++;
      }
    }
    for (const output of outputs) for (const ch of output) ch.fill(0);
    return true;
  }
}
registerProcessor('mono-pcm', MonoPcmWorklet);
