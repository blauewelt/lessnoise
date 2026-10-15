// Prints the label identifiers of Apple's built-in sound classifier (SoundAnalysis, version 1),
// one per line: the list ios/App/categories-ios.json maps from. Run on macOS 12+ (CI does):
//   swift ios/scripts/apple-sound-labels.swift > apple-sound-labels.txt
import SoundAnalysis

let request = try SNClassifySoundRequest(classifierIdentifier: .version1)
for id in request.knownClassifications { print(id) }
