-- Halo Graphic Engine device-side runtime (v1)
-- HRP is transported through the official data.lua message framing.

local HRP_CODE = 0x60
local CLICK_CODE = 0x0B
local TAP_CODE = 0x09
local STATUS_CODE = 0x70
local ERROR_CODE = 0x71
local MAX_HRP_BYTES = 32768

-- Minimal local equivalent of the official data.lua framing. Keeping this
-- small runtime self-contained avoids requiring a vendored SDK at upload time.
local pending = {}
local completed = {}
local completed_count = 0

local function send_event(code, payload)
    pcall(frame.bluetooth.send, string.char(code) .. (payload or ''))
end

local function receive_data(packet)
    if packet == nil or #packet < 1 then return end
    local flag = string.byte(packet, 1)
    local item = pending[flag]
    if item == nil then
        item = { size = 0, received = 0, chunks = {} }
        pending[flag] = item
    end
    if item.received == 0 then
        if #packet < 3 then
            pending[flag] = nil
            send_event(ERROR_CODE, 'invalid first packet')
            return
        end
        item.size = string.byte(packet, 2) << 8 | string.byte(packet, 3)
        if item.size > MAX_HRP_BYTES then
            pending[flag] = nil
            send_event(ERROR_CODE, 'message exceeds runtime limit')
            return
        end
        item.chunks[1] = string.sub(packet, 4)
        item.received = #packet - 3
    else
        item.chunks[#item.chunks + 1] = string.sub(packet, 2)
        item.received = item.received + #packet - 1
    end
    if item.received == item.size then
        completed_count = completed_count + 1
        completed[completed_count] = { flag, table.concat(item.chunks) }
        pending[flag] = nil
    elseif item.received > item.size then
        pending[flag] = nil
        send_event(ERROR_CODE, 'message length overflow')
        return
    end
    pcall(frame.bluetooth.send, '\x01\x00\x00')
end

local function process_raw_items()
    local items = completed
    completed = {}
    completed_count = 0
    collectgarbage('collect')
    return items
end

frame.bluetooth.receive_callback(receive_data)
local sprites = {}
local font_id = 0
local font_size = 8
local font_scale = 1

local function u16(s, p)
    return string.byte(s, p) << 8 | string.byte(s, p + 1)
end

local function color(s, p)
    return string.byte(s, p) << 16 | string.byte(s, p + 1) << 8 | string.byte(s, p + 2)
end

local function require_len(payload, position, needed)
    if position + needed - 1 > #payload then
        error('truncated HRP command')
    end
end

local function parse_sprite(payload)
    require_len(payload, 1, 9)
    local id = u16(payload, 1)
    local raw = string.sub(payload, 3)
    local width = u16(raw, 1)
    local height = u16(raw, 3)
    local compressed = string.byte(raw, 5) > 0
    local bpp = string.byte(raw, 6)
    local num_colors = string.byte(raw, 7)
    if compressed then
        error('compressed HRP sprites are not enabled')
    end
    if bpp ~= 1 and bpp ~= 2 and bpp ~= 4 then
        error('invalid HRP sprite bpp')
    end
    local palette_start = 8
    local palette_len = num_colors * 3
    require_len(raw, palette_start, palette_len)
    local pixels = string.sub(raw, palette_start + palette_len)
    sprites[id] = {
        width = width,
        height = height,
        bpp = bpp,
        num_colors = num_colors,
        palette_data = string.sub(raw, palette_start, palette_start + palette_len - 1),
        pixel_data = pixels
    }
end

local function execute(payload)
    if #payload > MAX_HRP_BYTES then
        error('HRP frame exceeds runtime limit')
    end
    if string.sub(payload, 1, 4) ~= 'HRP1' or string.byte(payload, 5) ~= 0 then
        error('invalid HRP header')
    end
    local count = u16(payload, 6)
    local position = 8
    for _ = 1, count do
        require_len(payload, position, 3)
        local opcode = string.byte(payload, position)
        local length = u16(payload, position + 1)
        position = position + 3
        require_len(payload, position, length)
        local command = string.sub(payload, position, position + length - 1)
        position = position + length

        if opcode == 0x01 then
            require_len(command, 1, 3)
            frame.display.clear(color(command, 1))
        elseif opcode == 0x02 then
            require_len(command, 1, 1)
            frame.display.brightness(string.byte(command, 1))
        elseif opcode == 0x03 then
            require_len(command, 1, 7)
            frame.display.set_pixel(u16(command, 1) + 1, u16(command, 3) + 1, color(command, 5))
        elseif opcode == 0x04 then
            require_len(command, 1, 11)
            frame.display.line(u16(command, 1) + 1, u16(command, 3) + 1, u16(command, 5) + 1, u16(command, 7) + 1, color(command, 9))
        elseif opcode == 0x05 then
            require_len(command, 1, 12)
            frame.display.rect(u16(command, 1) + 1, u16(command, 3) + 1, u16(command, 5), u16(command, 7), color(command, 9), string.byte(command, 12) ~= 0)
        elseif opcode == 0x06 then
            require_len(command, 1, 10)
            frame.display.circle(u16(command, 1) + 1, u16(command, 3) + 1, u16(command, 5), color(command, 7), string.byte(command, 10) ~= 0)
        elseif opcode == 0x07 then
            require_len(command, 1, 4)
            local points = string.byte(command, 1)
            if points > 64 then error('too many polygon points') end
            local expected = 1 + points * 4 + 3
            require_len(command, 1, expected)
            local coords = {}
            for i = 0, points - 1 do
                coords[i * 2 + 1] = u16(command, 2 + i * 4) + 1
                coords[i * 2 + 2] = u16(command, 4 + i * 4) + 1
            end
            frame.display.polygon(coords, color(command, 2 + points * 4))
        elseif opcode == 0x08 then
            require_len(command, 1, 3)
            font_id = string.byte(command, 1)
            font_size = string.byte(command, 2)
            font_scale = string.byte(command, 3)
            frame.display.set_font(font_id, font_size, font_scale)
        elseif opcode == 0x09 then
            require_len(command, 1, 9)
            local text_len = u16(command, 8)
            require_len(command, 1, 9 + text_len)
            frame.display.text(string.sub(command, 10, 9 + text_len), u16(command, 1) + 1, u16(command, 3) + 1, color(command, 5))
        elseif opcode == 0x0A then
            parse_sprite(command)
        elseif opcode == 0x0B then
            require_len(command, 1, 7)
            local sprite = sprites[u16(command, 1)]
            if sprite == nil then error('sprite resource not found') end
            frame.display.bitmap(u16(command, 3) + 1, u16(command, 5) + 1, sprite.width, 2 ^ sprite.bpp, string.byte(command, 7), sprite.pixel_data, { palette_data = sprite.palette_data })
        elseif opcode == 0x0C then
            require_len(command, 1, 2)
            sprites[u16(command, 1)] = nil
            collectgarbage('collect')
        elseif opcode == 0x0D then
            -- Dirty regions are currently a host optimization hint. Halo has no hardware clip API.
        elseif opcode == 0x0E then
            -- Halo draws immediately; no show() call is needed.
        elseif opcode == 0x0F then
            -- Feature negotiation is handled by the host profile in v1.
        else
            error('unknown HRP opcode ' .. tostring(opcode))
        end
    end
    if position ~= #payload + 1 then
        error('trailing HRP bytes')
    end
    collectgarbage('collect')
end

frame.button.single(function() send_event(CLICK_CODE, string.char(1)) end)
frame.button.double(function() send_event(CLICK_CODE, string.char(2)) end)
frame.button.long(function() send_event(CLICK_CODE, string.char(3)) end)
frame.imu.tap_callback(function(kind)
    local codes = { single = 1, double = 2, triple = 3 }
    send_event(TAP_CODE, string.char(codes[kind] or 1))
end)
frame.display.power_save(false)
send_event(STATUS_CODE, 'HRP1;primitives,sprites,click,tap')
print('Halo Engine HRP ready')

while true do
    local ok, err = pcall(function()
        local items = process_raw_items()
        for i = 1, #items do
            if items[i][1] == HRP_CODE then
                execute(items[i][2])
            end
        end
        frame.sleep(0.001)
    end)
    if not ok then
        send_event(ERROR_CODE, tostring(err))
        print(err)
    end
end
