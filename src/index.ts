import { registerPlugin } from '@capacitor/core';

import type { CapacitorVideoProcessorPluginPlugin } from './definitions';

const CapacitorVideoProcessorPlugin = registerPlugin<CapacitorVideoProcessorPluginPlugin>(
  'CapacitorVideoProcessorPlugin',
  {
    web: () => import('./web').then((m) => new m.CapacitorVideoProcessorPluginWeb()),
  },
);

export * from './definitions';
export { CapacitorVideoProcessorPlugin };
