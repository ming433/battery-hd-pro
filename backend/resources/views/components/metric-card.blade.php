@props([
    'label' => '',
    'value' => '—',
    'warn'  => false,
])

{{-- 指标卡片。warn 用于把超阈值的关键指标标红，避免异常被淹没在数字里。 --}}
<div class="bg-white rounded shadow px-3 py-3">
    <div class="text-xs text-slate-500">{{ $label }}</div>
    <div class="mt-1 text-lg font-semibold {{ $warn ? 'text-red-600' : 'text-slate-800' }}">
        {{ $value }}
    </div>
</div>
