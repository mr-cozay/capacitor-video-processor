#import <Capacitor/Capacitor.h>
#import <Foundation/Foundation.h>

// Ce fichier est OBLIGATOIRE : il expose les méthodes Swift à Capacitor via le
// runtime Objective-C.
CAP_PLUGIN(VideoProcessorPlugin, "VideoProcessor",
           CAP_PLUGIN_METHOD(compressVideo, CAPPluginReturnPromise);)