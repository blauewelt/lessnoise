/** A string-keyed map that keeps insertion order (Kotlin's `LinkedHashMap`). */
public struct OrderedDict<Value> {
    public private(set) var keys: [String] = []
    private var storage: [String: Value] = [:]

    public init() {}

    public init(_ pairs: [(String, Value)]) {
        for (k, v) in pairs { self[k] = v }
    }

    public subscript(key: String) -> Value? {
        get { storage[key] }
        set {
            if let v = newValue {
                if storage[key] == nil { keys.append(key) }
                storage[key] = v
            } else if storage.removeValue(forKey: key) != nil {
                keys.removeAll { $0 == key }
            }
        }
    }

    public var count: Int { keys.count }
    public var isEmpty: Bool { keys.isEmpty }
    public var entries: [(key: String, value: Value)] { keys.map { ($0, storage[$0]!) } }
    public var values: [Value] { keys.map { storage[$0]! } }

    public func mapValues<T>(_ f: (Value) -> T) -> OrderedDict<T> {
        var o = OrderedDict<T>()
        for k in keys { o[k] = f(storage[k]!) }
        return o
    }
}

extension OrderedDict: Equatable where Value: Equatable {
    public static func == (a: OrderedDict, b: OrderedDict) -> Bool { a.keys == b.keys && a.storage == b.storage }
}
