import { registerPlugin } from '@capacitor/core';

import type { VideoProcessorPlugin } from './definitions';

/**
 * Point d'entrée principal du plugin.
 * Capacitor choisit automatiquement l'implémentation native (Android/iOS)
 * ou le fallback Web selon la plateforme.
 */
const VideoProcessor = registerPlugin<VideoProcessorPlugin>('VideoProcessor', {
  web: () => import('./web').then((m) => new m.VideoProcessorWeb()),
});

export * from './definitions';
export { VideoProcessor };
