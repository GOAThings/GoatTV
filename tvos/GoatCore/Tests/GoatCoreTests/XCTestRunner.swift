#if canImport(XCTest)
import XCTest

/// Corre os testes do núcleo no Xcode (`swift test` num Mac).
final class GoatCoreTests: XCTestCase {
    func testAll() {
        for (name, test) in CoreTests.all {
            do {
                try test()
            } catch {
                XCTFail("\(name): \(error)")
            }
        }
    }
}
#endif
