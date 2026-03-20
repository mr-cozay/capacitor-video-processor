import { WebPlugin } from '@capacitor/core';

import type { CompressVideoOptions, CompressVideoResult, VideoProcessorPlugin } from './definitions';

/**
 * Fallback Web — utilisé uniquement en mode navigateur/PWA.
 * La compression vidéo réelle n'est pas disponible sur le Web :
 * on retourne le chemin d'entrée tel quel (no-op).
 *
 * Pour une vraie compression web, tu pourrais intégrer ffmpeg.wasm ici.
 */
export class VideoProcessorWeb extends WebPlugin implements VideoProcessorPlugin {
  async compressVideo(options: CompressVideoOptions): Promise<CompressVideoResult> {
    console.warn(
      "[VideoProcessor] La compression native n'est pas disponible sur le Web. " +
        'Le fichier source est retourné sans modification.',
    );
    return { output: options.input };
  }
}
