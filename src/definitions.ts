export interface CompressVideoOptions {
  /**
   * Chemin absolu vers la vidéo source (ex: file:///storage/...)
   */
  input: string;

  /**
   * Chemin absolu de destination pour la vidéo compressée
   */
  output: string;
}

export interface CompressVideoResult {
  /**
   * Chemin absolu du fichier de sortie compressé
   */
  output: string;
}

export interface VideoProcessorPlugin {
  /**
   * Compresse une vidéo en H.264 : résolution entre 480p et 720p (ratio conservé),
   * débit ~1,4–2,5 Mbps selon la hauteur ; AAC 128 kbps.
   *
   * @since 1.0.0
   */
  compressVideo(options: CompressVideoOptions): Promise<CompressVideoResult>;
}
