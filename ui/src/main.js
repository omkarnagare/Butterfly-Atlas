import './styles/fonts.css';
import './styles/theme.css';
import './styles/oakblue.css';
import './styles/atlas.css';
import { mount } from 'svelte';
import App from './App.svelte';
import { startSession } from './lib/session.svelte.js';
import { startTheme } from './lib/theme.svelte.js';
import { takeOn } from './lib/newWindow.js';

// Phase 6: the one session stream is opened before the page asks anything (contract §2).
startSession();
// Meadow: the theme this window showed last goes on the page before it is first drawn, then the server's choice.
startTheme();

// A window opened by New window starts where the window that asked for it was.
const at = takeOn(window.location.hash);
if (at) window.location.replace(at);

mount(App, { target: document.getElementById('app') });
