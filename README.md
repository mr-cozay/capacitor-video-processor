# capacitor-video-processor

Capacitor video processor for Android and iOS app

## Install

To use npm

```bash
npm install capacitor-video-processor
````

To use yarn

```bash
yarn add capacitor-video-processor
```

Sync native files

```bash
npx cap sync
```

## API

<docgen-index>

* [`compressVideo(...)`](#compressvideo)
* [Interfaces](#interfaces)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### compressVideo(...)

```typescript
compressVideo(options: CompressVideoOptions) => Promise<CompressVideoResult>
```

Compresse une vidéo en H.264 720p / 1.5 Mbps / AAC 128 kbps.

| Param         | Type                                                                  |
| ------------- | --------------------------------------------------------------------- |
| **`options`** | <code><a href="#compressvideooptions">CompressVideoOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#compressvideoresult">CompressVideoResult</a>&gt;</code>

**Since:** 1.0.0

--------------------


### Interfaces


#### CompressVideoResult

| Prop         | Type                | Description                                  |
| ------------ | ------------------- | -------------------------------------------- |
| **`output`** | <code>string</code> | Chemin absolu du fichier de sortie compressé |


#### CompressVideoOptions

| Prop         | Type                | Description                                                  |
| ------------ | ------------------- | ------------------------------------------------------------ |
| **`input`**  | <code>string</code> | Chemin absolu vers la vidéo source (ex: file:///storage/...) |
| **`output`** | <code>string</code> | Chemin absolu de destination pour la vidéo compressée        |

</docgen-api>
