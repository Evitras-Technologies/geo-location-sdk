require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "react-native-open-background-geolocation"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["repository"]["url"]
  s.license      = package["license"]
  s.authors      = "react-native-open-background-geolocation contributors"

  s.platforms    = { :ios => "13.0" }
  s.source       = { :git => package["repository"]["url"], :tag => "#{s.version}" }

  s.source_files = "ios/**/*.{h,m,mm,swift}"
  s.swift_version = "5.0"

  s.frameworks = "CoreLocation", "CoreMotion", "AudioToolbox", "MessageUI"
  s.libraries  = "sqlite3"

  s.dependency "React-Core"
end
