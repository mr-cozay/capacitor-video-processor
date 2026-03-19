import { WebPlugin } from '@capacitor/core';

import type { CapacitorVideoProcessorPluginPlugin } from './definitions';

export class CapacitorVideoProcessorPluginWeb extends WebPlugin implements CapacitorVideoProcessorPluginPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
