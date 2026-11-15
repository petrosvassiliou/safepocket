import './styles/m3-expressive.css';
import { SafePocketAppShell } from './ui/appShell';

const mountNode = document.getElementById('safepocket-app');
if (mountNode) {
  const app = new SafePocketAppShell(mountNode);
  void app.bootstrap();
}
