import Foundation

@objc public class CapacitorVideoProcessorPlugin: NSObject {
    @objc public func echo(_ value: String) -> String {
        print(value)
        return value
    }
}
