"""Scale configured regions to match an actual image size."""

from __future__ import annotations

from picap.models import Region


def scale_regions(
    regions: list[Region],
    ref_width: int,
    ref_height: int,
    image_width: int,
    image_height: int,
) -> list[Region]:
    if not regions or ref_width <= 0 or ref_height <= 0:
        return regions
    if ref_width == image_width and ref_height == image_height:
        return regions

    scale_x = image_width / ref_width
    scale_y = image_height / ref_height
    scaled: list[Region] = []
    for region in regions:
        scaled.append(
            Region(
                name=region.name,
                x=max(0, round(region.x * scale_x)),
                y=max(0, round(region.y * scale_y)),
                width=max(1, round(region.width * scale_x)),
                height=max(1, round(region.height * scale_y)),
                format=region.format,
            )
        )
    return scaled
