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
   * Compresse une vidéo en H.264 720p / 1.5 Mbps / AAC 128 kbps.
   *
   * @since 1.0.0
   */
  compressVideo(options: CompressVideoOptions): Promise<CompressVideoResult>;
}
