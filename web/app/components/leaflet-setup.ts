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
  html: '<div style="width:14px;height:14px;border-radius:50%;background:#3b82f6;border:2px solid white;box-shadow:0 0 2px rgba(0,0,0,0.6);"></div>',
  iconSize: [14, 14],
  iconAnchor: [7, 7],
});

export const positionIcon = L.divIcon({
  className: "gps-position",
  html: '<div style="width:18px;height:18px;border-radius:50%;background:#ef4444;border:3px solid white;box-shadow:0 0 4px rgba(0,0,0,0.6);"></div>',
  iconSize: [18, 18],
  iconAnchor: [9, 9],
});
