// Fix Leaflet's default marker icons under bundlers.
import L from "leaflet";
import iconRetina from "leaflet/dist/images/marker-icon-2x.png";
import icon from "leaflet/dist/images/marker-icon.png";
import iconShadow from "leaflet/dist/images/marker-shadow.png";

// react-leaflet uses L.Marker.prototype.options.icon; overriding the default fixes broken icons in bundlers.
const iconAny = (L.Icon.Default.prototype as { _getIconUrl?: unknown })
  ._getIconUrl;
if (iconAny) {
  delete (L.Icon.Default.prototype as { _getIconUrl?: unknown })._getIconUrl;
}
L.Icon.Default.mergeOptions({
  iconRetinaUrl: (iconRetina as unknown as { src: string }).src ?? iconRetina,
  iconUrl: (icon as unknown as { src: string }).src ?? icon,
  shadowUrl: (iconShadow as unknown as { src: string }).src ?? iconShadow,
});

export const waypointIcon = L.divIcon({
  className: "gps-waypoint",
  // Colors come from --route / --marker-* tokens in globals.css.
  html: '<div class="map-dot map-dot-waypoint"></div>',
  iconSize: [14, 14],
  iconAnchor: [7, 7],
});

export const positionIcon = L.divIcon({
  className: "gps-position",
  html: '<div class="map-dot map-dot-position"></div>',
  iconSize: [18, 18],
  iconAnchor: [9, 9],
});
