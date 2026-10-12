param([Parameter(Mandatory = $true)][string]$Out)
# The image viewer's corpus files made by real Windows software (Windows PowerShell 5.1):
# System.Drawing (GDI+) for PNG, JPEG, BMP, GIF and the EXIF orientation; WPF's GifBitmapEncoder for an
# animated GIF. Each picture says where its top is, so an orientation applied wrongly shows.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName PresentationCore, WindowsBase

function New-Picture([int]$Width, [int]$Height) {
    $bmp = New-Object System.Drawing.Bitmap($Width, $Height)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $rect = New-Object System.Drawing.Rectangle(0, 0, $Width, $Height)
    $gradient = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, [System.Drawing.Color]::LightYellow, [System.Drawing.Color]::MediumSeaGreen, 90.0)
    $g.FillRectangle($gradient, $rect)
    $band = [int]($Height / 6)
    $g.FillRectangle([System.Drawing.Brushes]::SteelBlue, 0, 0, $Width, $band)
    $font = New-Object System.Drawing.Font('Arial', [single]($band * 0.6), [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $g.DrawString('TOP', $font, [System.Drawing.Brushes]::White, 8, [single]($band * 0.15))
    # An arrow pointing up, in the middle.
    $cx = [int]($Width / 2); $cy = [int]($Height / 2); $s = [int]([Math]::Min($Width, $Height) / 5)
    $arrow = @(
        (New-Object System.Drawing.Point($cx, ($cy - $s))),
        (New-Object System.Drawing.Point(($cx + $s), $cy)),
        (New-Object System.Drawing.Point(($cx + [int]($s / 3)), $cy)),
        (New-Object System.Drawing.Point(($cx + [int]($s / 3)), ($cy + $s))),
        (New-Object System.Drawing.Point(($cx - [int]($s / 3)), ($cy + $s))),
        (New-Object System.Drawing.Point(($cx - [int]($s / 3)), $cy)),
        (New-Object System.Drawing.Point(($cx - $s), $cy))
    )
    $g.FillPolygon([System.Drawing.Brushes]::OrangeRed, $arrow)
    $g.Dispose()
    return $bmp
}

$jpeg = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
$quality = New-Object System.Drawing.Imaging.EncoderParameters(1)
$quality.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [long]85)

$picture = New-Picture 640 480
$picture.Save((Join-Path $Out 'system-drawing.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$picture.Save((Join-Path $Out 'system-drawing.jpg'), $jpeg, $quality)
# The BMP smaller: GDI+ writes it uncompressed, 32 bits a pixel.
$small = New-Picture 320 240
$small.Save((Join-Path $Out 'system-drawing.bmp'), [System.Drawing.Imaging.ImageFormat]::Bmp)
$small.Dispose()
$picture.Save((Join-Path $Out 'system-drawing.gif'), [System.Drawing.Imaging.ImageFormat]::Gif)
$picture.Dispose()

# A camera held upright: the picture is 480 x 640 upright, STORED turned a quarter to the left (640 x 480),
# with EXIF orientation 6 ("rotate 90 degrees clockwise to show"). Shown right, TOP is at the top.
$upright = New-Picture 480 640
$upright.RotateFlip([System.Drawing.RotateFlipType]::Rotate270FlipNone)
$item = [System.Runtime.Serialization.FormatterServices]::GetUninitializedObject([System.Drawing.Imaging.PropertyItem])
$item.Id = 0x0112
$item.Type = 3
$item.Len = 2
$item.Value = [byte[]](6, 0)
$upright.SetPropertyItem($item)
$upright.Save((Join-Path $Out 'system-drawing-exif-orientation-6.jpg'), $jpeg, $quality)
$upright.Dispose()

# An animated GIF of eight frames: a ball crossing the picture (WPF's encoder writes no frame delay).
$encoder = New-Object System.Windows.Media.Imaging.GifBitmapEncoder
for ($i = 0; $i -lt 8; $i++) {
    $visual = New-Object System.Windows.Media.DrawingVisual
    $context = $visual.RenderOpen()
    $context.DrawRectangle([System.Windows.Media.Brushes]::White, $null, (New-Object System.Windows.Rect(0, 0, 120, 80)))
    $context.DrawEllipse([System.Windows.Media.Brushes]::OrangeRed, $null, (New-Object System.Windows.Point((12 + $i * 13), 40)), 10, 10)
    $context.Close()
    $frame = New-Object System.Windows.Media.Imaging.RenderTargetBitmap(120, 80, 96, 96, [System.Windows.Media.PixelFormats]::Pbgra32)
    $frame.Render($visual)
    $encoder.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($frame))
}
$stream = [System.IO.File]::Create((Join-Path $Out 'wpf-animated.gif'))
try { $encoder.Save($stream) } finally { $stream.Close() }
Get-ChildItem $Out | Select-Object Name, Length | Format-Table -AutoSize
