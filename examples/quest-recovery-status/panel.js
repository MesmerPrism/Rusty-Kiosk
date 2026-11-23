'use strict';

const fileInput = document.querySelector('#report-file');
const sampleChoice = document.querySelector('#sample');
const sourceLabel = document.querySelector('#source');
const clearButton = document.querySelector('#clear');
const cards = Array.from(document.querySelectorAll('[data-signal]'));
const clock = new Intl.DateTimeFormat(undefined, { dateStyle: 'short', timeStyle: 'medium' });
const MAX_BYTES = 1024 * 1024;
const SCHEMA = 'rusty-kiosk.recovery-status.v1';
let observation = null;
let selection = 0;

function render() {
  const view = QuestRecoveryStatus.projectRecovery(observation);
  for (const element of cards) {
    const result = view[element.dataset.signal];
    const indicator = element.querySelector('.result');
    indicator.dataset.tone = result.tone;
    indicator.textContent = result.label;
    const time = element.querySelector('time');
    time.hidden = result.observedAt === null;
    if (time.hidden) {
      time.removeAttribute('datetime');
      time.textContent = '';
    } else {
      const observed = new Date(result.observedAt);
      time.dateTime = observed.toISOString();
      time.textContent = `Observed ${clock.format(observed)}`;
    }
  }
}

function setReport(value, description, busy = false) {
  observation = value;
  sourceLabel.textContent = description;
  clearButton.disabled = !observation && !busy;
  render();
}

fileInput.addEventListener('change', async () => {
  const file = fileInput.files[0];
  fileInput.value = '';
  if (!file) return;
  const request = ++selection;
  setReport(null, 'Opening local report…', true);
  try {
    if (file.size > MAX_BYTES) throw new Error('report size');
    const report = JSON.parse(await file.text());
    if (request !== selection) return;
    if (report?.schema !== SCHEMA) throw new Error('report schema');
    setReport(report, 'Local report loaded. Each observation is checked independently.');
  } catch {
    if (request === selection) setReport(null, 'Could not open this report. Choose a recovery JSON file under 1 MiB.');
  }
});

document.querySelector('#load-sample').addEventListener('click', () => {
  ++selection;
  setReport(QuestRecoverySamples.sampleReport(sampleChoice.value), 'Simulated sample');
});

clearButton.addEventListener('click', () => {
  ++selection;
  setReport(null, 'Open a local report or load a simulated sample.');
});

setInterval(() => { if (observation) render(); }, 1000);
render();
