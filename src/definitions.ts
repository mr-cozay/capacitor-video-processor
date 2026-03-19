export interface CapacitorVideoProcessorPluginPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
