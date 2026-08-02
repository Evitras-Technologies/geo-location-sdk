import CoreLocation
import Foundation

/// Geometry for polygon geofences — arbitrary shapes such as parks, building
/// footprints or delivery zones.
///
/// iOS can only monitor circular regions, so the SDK registers the polygon's
/// bounding circle with CoreLocation (preserving native wake-ups even when the
/// app is terminated) and decides true containment itself with a
/// point-in-polygon test.
enum PolygonGeometry {

    struct Vertex {
        let latitude: Double
        let longitude: Double
    }

    static func parseVertices(_ json: [String: Any]) -> [Vertex]? {
        guard let raw = json["vertices"] as? [Any], raw.count >= 3 else { return nil }

        var vertices: [Vertex] = []
        for entry in raw {
            if let pair = entry as? [Double], pair.count >= 2 {
                vertices.append(Vertex(latitude: pair[0], longitude: pair[1]))
            } else if let pair = entry as? [NSNumber], pair.count >= 2 {
                vertices.append(Vertex(latitude: pair[0].doubleValue, longitude: pair[1].doubleValue))
            } else if let object = entry as? [String: Any],
                      let latitude = (object["latitude"] as? NSNumber)?.doubleValue,
                      let longitude = (object["longitude"] as? NSNumber)?.doubleValue {
                vertices.append(Vertex(latitude: latitude, longitude: longitude))
            } else {
                return nil
            }
        }
        return vertices
    }

    static func isPolygon(_ json: [String: Any]) -> Bool {
        ((json["vertices"] as? [Any])?.count ?? 0) >= 3
    }

    /// Ray-casting containment test; handles concave shapes of any vertex count.
    static func contains(_ vertices: [Vertex], latitude: Double, longitude: Double) -> Bool {
        var inside = false
        var j = vertices.count - 1
        for i in vertices.indices {
            let vertexI = vertices[i]
            let vertexJ = vertices[j]
            let straddlesRay = (vertexI.latitude > latitude) != (vertexJ.latitude > latitude)
            if straddlesRay {
                let intersectionLongitude = vertexJ.longitude +
                    (latitude - vertexJ.latitude) /
                    (vertexI.latitude - vertexJ.latitude) *
                    (vertexI.longitude - vertexJ.longitude)
                if longitude < intersectionLongitude { inside.toggle() }
            }
            j = i
        }
        return inside
    }

    static func centroid(_ vertices: [Vertex]) -> CLLocationCoordinate2D {
        let latitude = vertices.reduce(0) { $0 + $1.latitude } / Double(vertices.count)
        let longitude = vertices.reduce(0) { $0 + $1.longitude } / Double(vertices.count)
        return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    /// Radius in metres covering every vertex, plus slack so entry is never missed.
    static func boundingRadius(_ vertices: [Vertex], centre: CLLocationCoordinate2D) -> Double {
        let centreLocation = CLLocation(latitude: centre.latitude, longitude: centre.longitude)
        let maximum = vertices.reduce(0.0) { longest, vertex in
            let distance = centreLocation.distance(
                from: CLLocation(latitude: vertex.latitude, longitude: vertex.longitude)
            )
            return max(longest, distance)
        }
        return maximum + boundingSlackMeters
    }

    private static let boundingSlackMeters = 50.0
}
